package td

/** The entire game rule set, as a pure function of (state, time, input).
  *
  * Nothing here touches libGDX, so the simulation can be stepped headlessly.
  * Tower/enemy types are spec names resolved against the active config. Wave
  * composition is explicit (per-type counts), so spawning is deterministic and
  * needs no rng.
  */
object Sim:

  def initial(seed: Long = RunContext.DefaultSeed): World =
    initial(RunGen.roll(seed))

  def initial(run: RunContext): World = World(
    level = 0,
    towers = Vector.empty,
    enemies = Vector.empty,
    shots = Vector.empty,
    gold = startGold(run, 0),
    lives = Config.StartLives + Meta.effects(run.talents).extraLives,
    wave = 0,
    spawnQueue = Vector.empty,
    spawnTimer = 0f,
    breakTimer = Waves.breakTime, // grace period before the level's first wave
    nextId = 1L,
    run = run
  )

  /** Gold a level starts with: the level's own start gold plus the run's talent
    * bonus. Gold never carries over between levels.
    */
  def startGold(run: RunContext, level: Int): Int =
    Config.withBiome(Config.biomeAt(run.biome, level))(Config.levelStartGold(level)) +
      Meta.effects(run.talents).startGold

  def step(w0: World, dt: Float, cmds: Vector[Command]): World =
    // Resolve the run's biome so route/level/palette lookups inside the step
    // (and downstream callers) see the right board. Cleared afterwards so the
    // global state never carries a stale biome between steps/runs.
    Config.setActive(w0.biome, w0.level)
    try
      if w0.isOver || w0.won then w0
      else if w0.draft.isDefined then cmds.foldLeft(w0)(applyCommand)
      else
        val withCmds = cmds.foldLeft(w0)(applyCommand)
        val spawned  = spawn(withCmds, dt)
        val auras    = applyAuras(spawned)
        val effected = tickEffects(auras, dt)
        val moved    = moveEnemies(effected, dt)
        val fired    = fireTowers(moved, dt)
        val resolved = moveProjectiles(fired, dt)
        advanceWave(resolved, dt)
    finally Config.clearActiveBiome()

  // -- input -----------------------------------------------------------------

  private def applyCommand(w: World, c: Command): World = c match
    case Command.Build(cell, tpe) =>
      val spec = Config.towerSpec(tpe)
      if w.draft.isEmpty && canBuild(w, cell, tpe) then
        w.copy(
          towers = w.towers :+ Tower(cell, 0f, tpe),
          gold = w.gold - spec.cost
        )
      else w

    case Command.PickRelic(relicId) =>
      w.draft match
        case Some(d) if d.offers.contains(DraftOffer.Relic(relicId)) && !w.run.relics.contains(relicId) =>
          beginNextLevel(w.copy(run = w.run.copy(relics = w.run.relics :+ relicId)))
        case _ => w

    case Command.PickAugment(augmentId) =>
      w.draft match
        case Some(d) if d.offers.contains(DraftOffer.Augment(augmentId)) && !w.run.augments.contains(augmentId) =>
          beginNextLevel(w.copy(
            run = w.run.copy(augments = w.run.augments :+ augmentId),
            pendingAugment = Some(augmentId)
          ))
        case _ => w

    case Command.Reroll() =>
      w.draft match
        case Some(d) if canReroll(w, d) =>
          w.copy(
            draft = Some(Draft(nextOffers(w), d.rerollCost + Config.RerollCostGrowth)),
            draftCursor = w.draftCursor + Config.OfferCount,
            gold = w.gold - d.rerollCost
          )
        case _ => w

    case Command.AttachAugment(cell) =>
      if canAttachAugment(w, cell) then
        val aug = w.pendingAugment.get
        val idx = w.towers.indexWhere(_.cell == cell)
        w.copy(
          towers = w.towers.updated(idx, w.towers(idx).copy(augment = Some(aug))),
          pendingAugment = None
        )
      else w

    case Command.GrantGold(amount) =>
      w.copy(gold = w.gold + amount)

    case Command.UnlockTowers() =>
      val names = Config.game.towers.map(_.name)
      w.copy(run = w.run.copy(loadout = (w.run.loadout ++ names).distinct))

  /** Whether a build command would be accepted — used by the renderer to colour
    * the placement preview, so preview and rule can never disagree.
    */
  def canBuild(w: World, cell: GridPos, tpe: String): Boolean =
    val spec = Config.towerSpec(tpe)
    (w.run.loadout.isEmpty || w.run.loadout.contains(tpe)) &&
      w.gold >= spec.cost &&
      Grid.isBuildable(cell, w.level) &&
      !w.towers.exists(_.cell == cell)

  /** Whether clicking a cell would socket the held augment into the tower there.
    * Shared by the renderer preview and the rule.
    */
  def canAttachAugment(w: World, cell: GridPos): Boolean =
    w.pendingAugment.isDefined && w.towers.exists(t => t.cell == cell && t.augment.isEmpty)

  // -- draft ------------------------------------------------------------------

  /** The next round of the pre-rolled pool, minus anything the run already owns
    * (the pool can repeat across rounds, and an owned pick would be refused).
    */
  private def nextOffers(w: World): Vector[DraftOffer] =
    w.run.draftPool.slice(w.draftCursor, w.draftCursor + Config.OfferCount).filterNot {
      case DraftOffer.Relic(id)   => w.run.relics.contains(id)
      case DraftOffer.Augment(id) => w.run.augments.contains(id)
    }

  private def canReroll(w: World, d: Draft): Boolean =
    effects(w).rerollUnlocked && w.gold >= d.rerollCost && nextOffers(w).nonEmpty

  // -- environment rules -------------------------------------------------------

  private def biomeEnv(w: World): Option[Effect] =
    Config.biomes.find(_.id == w.biome).flatMap(_.environment)

  private def envParam(w: World, kind: String, key: String): Float =
    biomeEnv(w) match
      case Some(e) if e.kind == kind => e.params.getOrElse(key, 0f)
      case _                         => 0f

  private def effects(w: World): TalentEffects = Meta.effects(w.run.talents)

  // -- twists ------------------------------------------------------------------

  /** The run's rolled twists, resolved to their config records (unknown ids are
    * ignored so a config reload that drops a twist degrades to "no twist").
    */
  private def activeTwists(w: World): Vector[Twist] =
    w.run.twists.flatMap(id => Config.twists.find(_.id == id))

  /** The composed twist effect for one tower kind. Multiple twists targeting the
    * same kind fold together (multipliers multiply, flags OR).
    */
  private def twistFx(w: World, towerName: String): TwistFx =
    activeTwists(w).filter(_.targetTower == towerName)
      .foldLeft(TwistFx.none)(_ combined TwistFx.from(_))

  /** Multiplier for kinetic damage against slowed enemies while Brittle is
    * active (1f when it is not). The silver lining is run-wide, not tower-bound.
    */
  private def brittleKineticMult(w: World): Float =
    activeTwists(w).find(_.effect.kind == "brittle")
      .map(_.effect.params.getOrElse("kineticDamageVsSlowed", 1f))
      .getOrElse(1f)

  /** Per-tower twist modifiers, composed from the run's active twists. */
  private case class TwistFx(
      slowDurationMult: Float = 1f,
      burnTickRate: Float = 1f,
      noBurnStack: Boolean = false,
      radiusMult: Float = 1f,
      armorPierce: Boolean = false,
      bounceEvery: Int = 0,
      bounceRadius: Float = 0f,
      fireIntervalMult: Float = 1f
  ):
    def combined(o: TwistFx): TwistFx =
      TwistFx(
        slowDurationMult = slowDurationMult * o.slowDurationMult,
        burnTickRate = burnTickRate * o.burnTickRate,
        noBurnStack = noBurnStack || o.noBurnStack,
        radiusMult = radiusMult * o.radiusMult,
        armorPierce = armorPierce || o.armorPierce,
        bounceEvery = math.max(bounceEvery, o.bounceEvery),
        bounceRadius = math.max(bounceRadius, o.bounceRadius),
        fireIntervalMult = fireIntervalMult * o.fireIntervalMult
      )

  private object TwistFx:
    val none: TwistFx = TwistFx()
    def from(t: Twist): TwistFx =
      val p = t.effect.params
      t.effect.kind match
        case "brittle"      => TwistFx(slowDurationMult = p.getOrElse("slowDurationMult", 1f))
        case "backdraft"    => TwistFx(burnTickRate = p.getOrElse("burnTickRateMult", 1f), noBurnStack = p.getOrElse("maxBurnStacks", 1f) <= 1f)
        case "shapedCharge" => TwistFx(radiusMult = p.getOrElse("radiusMult", 1f), armorPierce = p.getOrElse("armorPierce", 0f) >= 1f)
        case "ricochet"     => TwistFx(bounceEvery = p.getOrElse("bounceEvery", 0f).toInt, bounceRadius = p.getOrElse("bounceRadius", 0f), fireIntervalMult = p.getOrElse("fireIntervalMult", 1f))
        case _              => TwistFx.none

  private def killReward(w: World): Float =
    (Config.KillReward + envParam(w, "recycling", "killGoldBonus") + relicKillBonus(w)) *
      effects(w).goldMult * relicGoldMult(w)

  /** Total gold for a wave of kills, summed per-enemy via `EnemySpec.killReward`
    * so bosses can pay proportionally more than baseline fodder.
    */
  private def killRewardTotal(w: World, dead: Vector[Enemy]): Int =
    dead.map(e => math.round(killReward(w) * Config.enemySpec(e.tpe).killReward)).sum.toInt

  /** Multiplicative gold multiplier from owned `squareDeal` relics. */
  private def relicGoldMult(w: World): Float =
    w.run.relics.foldLeft(1f) { (acc, id) =>
      val r = Config.relic(id)
      if r.effect.kind == "squareDeal" then acc * r.effect.params.getOrElse("goldMult", 1f) else acc
    }

  /** Additive kill-reward bonus from owned `boxOffice` relics. */
  private def relicKillBonus(w: World): Float =
    w.run.relics.foldLeft(0f) { (acc, id) =>
      val r = Config.relic(id)
      if r.effect.kind == "boxOffice" then acc + r.effect.params.getOrElse("killRewardBonus", 0f) else acc
    }

  private def vagueHint(base: Float): String =
    if base >= 0.99f then "immune"
    else if base > 0f then "resists"
    else if base < 0f then "weak"
    else "neutral"

  private def bumpObserved(
      hits: Map[(String, String), Int],
      events: Vector[DiscoveryEvent],
      tpe: String,
      key: String,
      studiedAfter: Int,
      sightHint: String,
      studiedValue: String
  ): (Map[(String, String), Int], Vector[DiscoveryEvent]) =
    val n = hits.getOrElse((tpe, key), 0) + 1
    val ev: Option[DiscoveryEvent] =
      if n == 1 && studiedAfter == 1 then Some(DiscoveryEvent(tpe, key, Precision.Studied, studiedValue))
      else if n == 1 then Some(DiscoveryEvent(tpe, key, Precision.Sighting, sightHint))
      else if n == studiedAfter then Some(DiscoveryEvent(tpe, key, Precision.Studied, studiedValue))
      else None
    (hits + ((tpe, key) -> n), ev.fold(events)(events :+ _))

  private def observeAbilities(w: World, spec: EnemySpec): World =
    val traits = Vector.newBuilder[(String, Int, String)]
    spec.aura.foreach(a => traits += (("aura", 1, s"${a.kind} ${a.magnitude}")))
    if spec.heavy then traits += (("heavy", 1, "true"))
    traits.result().foldLeft(w) { case (acc, (key, after, value)) =>
      val (hits, evs) = bumpObserved(acc.traitHits, acc.events, spec.name, key, after, "", value)
      acc.copy(traitHits = hits, events = evs)
    }

  /** First killing blow on a type reveals its name: a journal entry plus a
    * reveal flash at the death spot. Run-scoped via [[World.revealed]].
    */
  private def noteReveals(w: World, dead: Vector[Enemy]): World =
    val (revealed, events, names) = dead.foldLeft((w.revealed, w.events, w.nameEvents)) {
      case ((rev, evs, ns), e) =>
        if rev.contains(e.tpe) then (rev, evs, ns)
        else
          val (x, y) = e.pos
          (rev + e.tpe, evs :+ DiscoveryEvent(e.tpe, "name", Precision.Studied, e.tpe), ns :+ NameEvent(e.tpe, x, y, reveal = true))
    }
    w.copy(revealed = revealed, events = events, nameEvents = names)

  // -- wave pacing -----------------------------------------------------------

  private def spawn(w: World, dt: Float): World =
    if w.inBreak || w.spawnQueue.isEmpty then w
    else
      val t = w.spawnTimer - dt
      if t > 0f then w.copy(spawnTimer = t)
      else
        val name     = w.spawnQueue.head
        val spec     = Config.enemySpec(name)
        val hp       = spec.baseHp * Waves.spec(w.level, w.wave).hpScale
        val e        = Enemy(
          id = w.nextId,
          progress = 0f,
          hp = hp,
          maxHp = hp,
          speed = spec.baseSpeed * Config.CellPx * w.run.speedMults.getOrElse(spec.name, 1f),
          tpe = spec.name,
          level = w.level,
          resist = spec.resist.layered(w.resistTheme)
        )
        val (ex, ey) = e.pos
        val meet     = if w.seen.contains(spec.name) then Vector.empty else Vector(NameEvent(spec.name, ex, ey, reveal = false))
        observeAbilities(w, spec).copy(
          enemies = w.enemies :+ e,
          spawnQueue = w.spawnQueue.tail,
          spawnTimer = Waves.spec(w.level, w.wave).spawnInterval,
          nextId = w.nextId + 1,
          seen = w.seen + spec.name,
          nameEvents = w.nameEvents ++ meet
        )

  /** Counts down the break and starts the next wave; once a wave is fully
    * spawned and cleared, opens the next break or — when it was the level's last
    * wave — completes the level.
    */
  private def advanceWave(w: World, dt: Float): World =
    if w.inBreak then
      val t = w.breakTimer - dt
      if t > 0f then w.copy(breakTimer = t)
      else startWave(w)
    else if w.spawnQueue.isEmpty && w.enemies.isEmpty then
      if w.wave >= Waves.waveCount(w.level) then completeLevel(w)
      else w.copy(breakTimer = Waves.breakTime)
    else w

  private def startWave(w: World): World =
    val next = w.wave + 1
    val spec = Waves.spec(w.level, next)
    w.copy(
      breakTimer = 0f,
      wave = next,
      spawnQueue = scaledSpawnOrder(w, spec),
      spawnTimer = 0f // first enemy of the wave appears immediately
    )

  private def scaledSpawnOrder(w: World, spec: WaveSpec): Vector[String] =
    val mult = envParam(w, "recycling", "swarmSizeMult")
    if mult <= 0f || mult == 1f then Waves.spawnOrder(spec)
    else spec.composition.flatMap { case (name, n) =>
      Vector.fill(math.max(0, math.round(n * mult).toInt))(name)
    }

  /** End-of-level transition. Towers/enemies/shots are cleared for the next
    * route; lives carry over, gold resets to the next level's start gold. Winning the last
    * level sets [[World.won]] and freezes the sim.
    */
  private def completeLevel(w: World): World =
    if w.level + 1 >= Config.levelCount then
      w.copy(
        won = true,
        enemies = Vector.empty,
        shots = Vector.empty,
        spawnQueue = Vector.empty,
        breakTimer = 0f
      )
    else enterDraft(w)

  /** Between levels: clear the field and present the next pre-rolled offers.
    * The level number does NOT advance until the player picks.
    */
  private def enterDraft(w: World): World =
    val offers = nextOffers(w)
    // Nothing left to offer: skip the draft rather than leave the player stuck.
    if offers.isEmpty then beginNextLevel(w.copy(draftCursor = w.draftCursor + Config.OfferCount))
    else w.copy(
      draft = Some(Draft(offers, Config.RerollCost)),
      draftCursor = w.draftCursor + Config.OfferCount,
      towers = Vector.empty,
      enemies = Vector.empty,
      shots = Vector.empty,
      spawnQueue = Vector.empty,
      spawnTimer = 0f,
      breakTimer = Waves.breakTime,
      hazards = Vector.empty
    )

  /** A pick was made: advance to the next level. Towers/enemies/shots are
    * cleared, gold resets to the new level's start gold, and the draft closes. `pendingAugment`
    * (if any) is carried over for socketing during play.
    */
  private def beginNextLevel(w: World): World =
    w.copy(
      level = w.level + 1,
      wave = 0,
      towers = Vector.empty,
      enemies = Vector.empty,
      shots = Vector.empty,
      spawnQueue = Vector.empty,
      spawnTimer = 0f,
      breakTimer = Waves.breakTime,
      gold = startGold(w.run, w.level + 1),
      hazards = Vector.empty,
      draft = None
    )

  // -- auras ------------------------------------------------------------------

  /** Buffs each enemy from nearby aura carriers. Only the strongest source of
    * each buff kind counts, so stacking commanders can never balloon speed.
    */
  private def applyAuras(w: World): World =
    val floorPlan = biomeEnv(w) match
      case Some(e) if e.kind == "openFloorPlan" =>
        Some((e.params.getOrElse("radius", 0f) * Config.CellPx, e.params.getOrElse("armorPerNeighbor", 0f)))
      case _ => None
    val carriers = w.enemies.filter(_.spec.aura.isDefined)
    if carriers.isEmpty && floorPlan.isEmpty then w
    else
      w.copy(enemies = w.enemies.map { e =>
        var speedMult = 1f
        var auraArmor = 0f
        carriers.foreach { src =>
          if src.id != e.id then
            src.spec.aura.foreach { a =>
              val (sx, sy) = src.pos
              val (ex, ey) = e.pos
              val dx       = sx - ex
              val dy       = sy - ey
              val r = a.radius * Config.CellPx
              if dx * dx + dy * dy <= r * r then
                a.kind match
                  case AuraKind.SpeedBoost => speedMult = math.max(speedMult, 1f + a.magnitude)
                  case AuraKind.Armor      => auraArmor = math.max(auraArmor, a.magnitude)
                  case AuraKind.TowerChill => ()
            }
        }
        var armor = auraArmor
        floorPlan.foreach { case (radius, per) =>
          val (ex, ey) = e.pos
          val neighbors = w.enemies.count { o =>
            if o.id == e.id then false
            else
              val (ox, oy) = o.pos
              val dx = ox - ex; val dy = oy - ey
              dx * dx + dy * dy <= radius * radius
          }
          armor += per * neighbors
        }
        e.copy(speedMult = speedMult, armor = armor)
      })

  // -- movement --------------------------------------------------------------

  /** Burns tick down hp; slow timers count down. Any enemy that burns to death
    * is cashed in here.
    */
  private def tickEffects(w: World, dt: Float): World =
    val hazards = tickHazards(w.hazards, dt)
    val base = if hazards eq w.hazards then w else w.copy(hazards = hazards)
    if base.enemies.isEmpty then base
    else
      val updated = base.enemies.map { e =>
        val burned =
          if e.burning then e.copy(hp = e.hp - e.burnDps * dt, burnUntil = e.burnUntil - dt)
          else e
        if burned.slowed then burned.copy(slowUntil = burned.slowUntil - dt) else burned
      }
      val (dead, alive) = updated.partition(_.hp <= 0f)
      if dead.isEmpty then base.copy(enemies = alive)
      else
        spawnOnDeath(noteReveals(base, dead).copy(
          enemies = alive,
          gold = base.gold + killRewardTotal(base, dead),
          kills = base.kills + dead.size,
          hazards = base.hazards ++ crushedHazards(base, dead)
        ), dead)

  private def tickHazards(hazards: Vector[Hazard], dt: Float): Vector[Hazard] =
    if hazards.isEmpty then hazards
    else hazards.map(h => h.copy(remaining = h.remaining - dt)).filter(_.remaining > 0f)

  private def crushedHazards(w: World, dead: Vector[Enemy]): Vector[Hazard] =
    biomeEnv(w) match
      case Some(e) if e.kind == "crushed" =>
        val radius   = e.params.getOrElse("radius", 0f) * Config.CellPx
        val slowF    = e.params.getOrElse("slowFactor", 1f)
        val duration = e.params.getOrElse("hazardDuration", 0f)
        dead.filter(_.spec.heavy).map { d =>
          val (x, y) = d.pos
          Hazard(x, y, radius, slowF, duration)
        }
      case _ => Vector.empty

  private def moveEnemies(w: World, dt: Float): World =
    val (laned, hits, evs) = w.enemies.foldLeft((Vector.empty[Enemy], w.traitHits, w.events)) {
      case ((acc, hh, ee), e) =>
        val next = e.progress + e.effectiveSpeed * hazardMult(e, w.hazards) * dt
        val e2   = e.copy(progress = next)
        if e.lane.isDefined then (acc :+ e2, hh, ee)
        else
          Grid.forks(e.level).find(f => e.progress < f.forkProgress && next >= f.forkProgress) match
            case None => (acc :+ e2, hh, ee)
            case Some(f) =>
              e.spec.sidePreference match
                case Some(side) =>
                  val lane = if f.side == side then Some(f.branchIndex) else None
                  val (hh2, evs2) =
                    bumpObserved(hh, ee, e.tpe, "route", Config.StudyHits, "takes a side", side.toString.toLowerCase)
                  (acc :+ e2.copy(lane = lane), hh2, evs2)
                case None =>
                  val lane = if (laneRoll(w, e) & 1L) == 0L then None else Some(f.branchIndex)
                  (acc :+ e2.copy(lane = lane), hh, ee)
    }

    val base = w.copy(traitHits = hits, events = evs)
    val (leaked, still) = laned.partition(e => e.progress >= Grid.length(e.level))
    if leaked.isEmpty then base.copy(enemies = still)
    else
      val mult = math.max(0f, envParam(w, "recycling", "livesLossMult")) match
        case m if m <= 0f => 1f
        case m            => m
      val pending = w.leakDamage + leaked.map(_.spec.leakCost).sum * mult
      val whole   = pending.toInt
      val merged  = leaked.foldLeft(w.leaksByType) { (m, e) => m + (e.tpe -> (m.getOrElse(e.tpe, 0) + 1)) }
      base.copy(
        enemies = still,
        lives = math.max(0, w.lives - whole),
        leaks = w.leaks + leaked.size,
        leakDamage = pending - whole,
        leaksByType = merged
      )

  /** Pre-rolled lane choice for an enemy at its first fork, read from the run
    * (never consumed via RNG here). Indexed by the enemy's id with a safe wrap.
    */
  private def laneRoll(w: World, e: Enemy): Long =
    val choices = w.run.laneChoices
    if choices.isEmpty then 0L else choices(((e.id - 1) % choices.size).toInt)

  private def hazardMult(e: Enemy, hazards: Vector[Hazard]): Float =
    if hazards.isEmpty then 1f
    else
      val (ex, ey) = e.pos
      val factors = hazards.collect { case h if h.remaining > 0f =>
        val dx = h.x - ex; val dy = h.y - ey
        if dx * dx + dy * dy <= h.radius * h.radius then h.slowFactor else 1f
      }
      if factors.isEmpty then 1f else factors.min

  private case class AugmentFx(damageMult: Float, slow: Option[(Float, Float)])

  private def augmentFx(t: Tower): AugmentFx =
    t.augment match
      case None => AugmentFx(1f, None)
      case Some(id) =>
        val a = Config.augment(id)
        a.effect.kind match
          case "sharpenedEdge" => AugmentFx(a.effect.params.getOrElse("damageMult", 1f), None)
          case "vertigo"       => AugmentFx(1f, Some((a.effect.params.getOrElse("slowAmount", 0f), a.effect.params.getOrElse("slowDuration", 0f))))
          case _               => AugmentFx(1f, None)

  /** How fast a tower reloads: 1 normally, less while a chill aura (TowerChill)
    * reaches it. The strongest chill wins.
    */
  private def towerChill(w: World, t: Tower): Float =
    w.enemies.foldLeft(1f) { (acc, e) =>
      e.spec.aura match
        case Some(a) if a.kind == AuraKind.TowerChill =>
          val (ex, ey) = e.pos
          val r        = a.radius * Config.CellPx
          val dx       = ex - t.cell.centerX
          val dy       = ey - t.cell.centerY
          if dx * dx + dy * dy <= r * r then math.min(acc, 1f - a.magnitude) else acc
        case _ => acc
    }

  /** Enemies that die leave their spawn behind, at the same place on the route. */
  private def spawnOnDeath(w: World, dead: Vector[Enemy]): World =
    val spawns = dead.flatMap { d =>
      d.spec.onDeath.toVector.flatMap { case (name, count) =>
        val spec = Config.enemySpec(name)
        (0 until count).map(_ => d -> spec)
      }
    }
    if spawns.isEmpty then w
    else
      val (next, out) = spawns.foldLeft((w.nextId, Vector.empty[Enemy])) { case ((id, acc), (d, spec)) =>
        val e = Enemy(
          id = id,
          progress = d.progress,
          hp = spec.baseHp,
          maxHp = spec.baseHp,
          speed = spec.baseSpeed * Config.CellPx,
          tpe = spec.name,
          level = d.level,
          resist = spec.resist.layered(w.resistTheme),
          lane = d.lane
        )
        (id + 1, acc :+ e)
      }
      w.copy(enemies = w.enemies ++ out, nextId = next)

  private def fireTowers(w: World, dt: Float): World =
    val fresh = Vector.newBuilder[Projectile]
    val eff   = effects(w)

    val towers = w.towers.map { t =>
      val cd = t.cooldown - dt * towerChill(w, t)
      if cd > 0f then t.copy(cooldown = cd)
      else
        val spec   = t.spec
        val tw     = twistFx(w, t.tpe)
        val range  = spec.range * Config.CellPx * eff.rangeMult
        val range2 = range * range
        // Standard tower-defense targeting: whatever is closest to leaking.
        val target = w.enemies
          .filter { e =>
            val (ex, ey) = e.pos
            val dx       = ex - t.cell.centerX
            val dy       = ey - t.cell.centerY
            dx * dx + dy * dy <= range2
          }
          .maxByOption(_.progress)

        target match
          case None => t.copy(cooldown = 0f)
          case Some(e) =>
            val dest   = if spec.kind == DamageKind.Explosion then Some(e.pos) else None
            val fx     = augmentFx(t)
            val fired  = t.shotsFired + 1
            val bounce = tw.bounceEvery > 0 && fired % tw.bounceEvery == 0
            val crit   = eff.critChance > 0f && critRoll(w, t, fired) < eff.critChance
            val slowOv =
              if spec.kind == DamageKind.Slow then Some((Config.SlowFactor, Config.SlowDuration * tw.slowDurationMult))
              else fx.slow
            fresh += Projectile(
              t.cell.centerX,
              t.cell.centerY,
              e.id,
              spec.kind,
              spec.damage * eff.damageMult * eff.kindMult(spec.kind) * fx.damageMult *
                (if crit then eff.critMult else 1f),
              spec.projectileSpeed * Config.CellPx,
              spec.splashRadius * Config.CellPx * tw.radiusMult,
              dest,
              slowOv,
              burnTickRate = tw.burnTickRate,
              noBurnStack = tw.noBurnStack,
              armorPierce = tw.armorPierce,
              bounce = bounce,
              bounceRadius = tw.bounceRadius * Config.CellPx,
              crit = crit
            )
            t.copy(cooldown = spec.fireInterval * tw.fireIntervalMult / eff.fireRateMult, shotsFired = fired)
    }

    w.copy(towers = towers, shots = w.shots ++ fresh.result())

  /** A number in [0, 1) for a tower's `fired`-th shot. The sim has no random
    * state, so it is derived from the run seed, the tower's cell and the shot
    * count: the same run always crits on the same shots, and replays stay exact.
    */
  private def critRoll(w: World, t: Tower, fired: Int): Float =
    val mix = w.run.seed ^ (t.cell.x.toLong * 0x9E3779B97F4A7C15L) ^ (t.cell.y.toLong * 0xC2B2AE3D27D4EB4FL) ^
      (fired.toLong * 0x165667B19E3779F9L)
    Rng(mix).nextLong()._2.nextFloat()._1

  /** One landing shot's worth of effect on a single enemy. Resistances are not
    * applied here — each target reads its own resist/armor when the hit lands.
    */
  private case class Hit(
      id: Long,
      kind: DamageKind,
      damage: Float,
      burn: Option[(Float, Float)],
      slow: Option[(Float, Float)],
      noBurnStack: Boolean = false,
      armorPierce: Boolean = false,
      crit: Boolean = false
  )

  /** The shred talent: each hit lowers the target's resistance to that hit's
    * damage kind, down to its starting value minus the talent's maximum.
    */
  private def shred(w: World, e: Enemy, kind: DamageKind, eff: TalentEffects): Resistances =
    if eff.shredPerHit <= 0f then e.resist
    else
      val floor = math.max(-1f, e.spec.resist.layered(w.resistTheme)(kind) - eff.shredMax)
      e.resist.updated(kind, math.max(floor, e.resist(kind) - eff.shredPerHit))

  /** Moves each shot, converts the ones that land into hits, applies them, then
    * cashes in whatever died. Explosion shots detonate at their fixed point even
    * if the original target has already died.
    */
  private def moveProjectiles(w: World, dt: Float): World =
    val eff        = effects(w)
    val brittleKin = brittleKineticMult(w)
    val byId = w.enemies.map(e => e.id -> e).toMap
    val fs   = Vector.newBuilder[Projectile]
    val hs   = Vector.newBuilder[Hit]

    w.shots.foreach { p =>
      val stepLen = p.speed * dt
      if p.homes then
        byId.get(p.targetId) match
          case None => () // target already dead: the shot fizzles
          case Some(e) =>
            val (ex, ey) = e.pos
            val dx       = ex - p.x
            val dy       = ey - p.y
            val d        = math.hypot(dx, dy).toFloat
            if d <= stepLen + Config.HitRadius then hs ++= resolveImpact(w, p, (ex, ey))
            else fs += p.copy(x = p.x + dx / d * stepLen, y = p.y + dy / d * stepLen)
      else
        val (tx, ty) = p.dest.getOrElse((p.x, p.y))
        val dx       = tx - p.x
        val dy       = ty - p.y
        val d        = math.hypot(dx, dy).toFloat
        if d <= stepLen + Config.HitRadius then hs ++= resolveImpact(w, p, (tx, ty))
        else fs += p.copy(x = p.x + dx / d * stepLen, y = p.y + dy / d * stepLen)
    }

    val landed     = hs.result()
    val landedById = landed.groupBy(_.id)
    val fb         = Vector.newBuilder[HitFeedback]
    val puddles  = Grid.puddles(w.level)
    val damaged  = w.enemies.map { e =>
      landedById.get(e.id) match
        case None => e
        case Some(es) =>
          es.foldLeft(e) { (acc, h) =>
            // the current resist: earlier hits this step may have shredded it
            val resistFrac = acc.resist(h.kind)
            val wet        = if h.kind == DamageKind.Shock && inPuddle(e.pos, puddles) then Config.PuddleShockMult else 1f
            val base       = h.damage * (1f - resistFrac) * wet
            val armored    = if h.armorPierce then base else base * (1f - e.armor)
            val dmg        = math.max(0f,
              if brittleKin != 1f && h.kind == DamageKind.Kinetic && e.slowed then armored * brittleKin
              else armored)
            val (ex, ey) = e.pos
            fb += HitFeedback(ex, ey, h.kind, dmg, resistFrac, h.crit)
            var cur = acc.copy(hp = acc.hp - dmg, resist = shred(w, acc, h.kind, eff))
            h.burn.foreach { case (dps, dur) =>
              val fr = e.resist(DamageKind.Fire)
              val du = dur * (1f - fr)
              if du > 0f then
                if h.noBurnStack && acc.burning then ()
                else
                  cur = cur.copy(burnDps = math.max(cur.burnDps, dps * (1f - fr)), burnUntil = math.max(cur.burnUntil, du))
            }
            h.slow.foreach { case (factor, dur) =>
              val sr = e.resist(DamageKind.Slow)
              val du = dur * (1f - sr)
              if du > 0f then
                val f  = 1f - (1f - factor) * (1f - sr)
                val ff = if cur.slowed then math.min(cur.slowFactor, f) else f
                cur = cur.copy(slowFactor = ff, slowUntil = math.max(cur.slowUntil, du))
            }
            cur
          }
    }
    val (dead, alive) = damaged.partition(_.hp <= 0f)

    val idToTpe = w.enemies.map(e => e.id -> e.tpe).toMap
    val (newHits, newEvents) = landed.foldLeft((w.traitHits, w.events)) {
      case ((hh, ee), h) =>
        idToTpe.get(h.id) match
          case None => (hh, ee)
          case Some(tpe) =>
            val base = Config.enemySpec(tpe).resist(h.kind)
            bumpObserved(hh, ee, tpe, s"resist.${h.kind}", Config.StudyHits, vagueHint(base), base.toString)
    }

    val withResist = w.copy(traitHits = newHits, events = newEvents)
    spawnOnDeath(noteReveals(withResist, dead).copy(
      shots = fs.result(),
      enemies = alive,
      gold = w.gold + killRewardTotal(w, dead),
      kills = w.kills + dead.size,
      hazards = w.hazards ++ crushedHazards(w, dead),
      hitFeedback = w.hitFeedback ++ fb.result()
    ), dead)

  private def inPuddle(pos: (Float, Float), puddles: Vector[(Float, Float, Float)]): Boolean =
    val (x, y) = pos
    puddles.exists { case (px, py, r) =>
      val dx = x - px
      val dy = y - py
      dx * dx + dy * dy <= r * r
    }

  private def resolveImpact(w: World, p: Projectile, at: (Float, Float)): Vector[Hit] =
    val (ix, iy) = at
    val primary =
      if p.splashRadius > 0f then
        val r2 = p.splashRadius * p.splashRadius
        w.enemies.filter { e =>
          val (ex, ey) = e.pos
          val dx       = ex - ix
          val dy       = ey - iy
          dx * dx + dy * dy <= r2
        }
      else w.enemies.filter(_.id == p.targetId)

    def make(e: Enemy): Hit =
      val base = p.kind match
        case DamageKind.Kinetic   => Hit(e.id, DamageKind.Kinetic, p.damage, None, None, p.noBurnStack, p.armorPierce)
        case DamageKind.Fire      => Hit(e.id, DamageKind.Fire, p.damage, Some((Config.BurnDps * p.burnTickRate, Config.BurnDuration)), None, p.noBurnStack, p.armorPierce)
        case DamageKind.Slow      => Hit(e.id, DamageKind.Slow, p.damage, None, Some((Config.SlowFactor, Config.SlowDuration)), p.noBurnStack, p.armorPierce)
        case DamageKind.Splash    => Hit(e.id, DamageKind.Splash, p.damage, None, None, p.noBurnStack, p.armorPierce)
        case DamageKind.Shock     => Hit(e.id, DamageKind.Shock, p.damage, None, None, p.noBurnStack, p.armorPierce)
        case DamageKind.Explosion => Hit(e.id, DamageKind.Explosion, p.damage, None, None, p.noBurnStack, p.armorPierce)
      p.slow.fold(base)(s => base.copy(slow = Some(s))).copy(crit = p.crit)

    val hits = primary.map(make)
    if p.bounce then
      val r2     = p.bounceRadius * p.bounceRadius
      val hitIds = hits.map(_.id).toSet
      val second = w.enemies
        .filter(e => !hitIds.contains(e.id))
        .filter { e =>
          val (ex, ey) = e.pos
          val dx       = ex - ix
          val dy       = ey - iy
          dx * dx + dy * dy <= r2
        }
        .minByOption { e =>
          val (ex, ey) = e.pos
          val dx       = ex - ix
          val dy       = ey - iy
          dx * dx + dy * dy
        }
      second.fold(hits)(e2 => hits :+ make(e2))
    else hits
