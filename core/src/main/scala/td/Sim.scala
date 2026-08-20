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
    gold = Config.StartGold,
    lives = Config.StartLives,
    wave = 0,
    spawnQueue = Vector.empty,
    spawnTimer = 0f,
    breakTimer = Waves.breakTime, // grace period before the level's first wave
    nextId = 1L,
    run = run
  )

  def step(w0: World, dt: Float, cmds: Vector[Command]): World =
    if w0.isOver || w0.won then w0
    else
      val withCmds = cmds.foldLeft(w0)(applyCommand)
      val spawned  = spawn(withCmds, dt)
      val auras    = applyAuras(spawned)
      val effected = tickEffects(auras, dt)
      val moved    = moveEnemies(effected, dt)
      val fired    = fireTowers(moved, dt)
      val resolved = moveProjectiles(fired, dt)
      advanceWave(resolved, dt)

  // -- input -----------------------------------------------------------------

  private def applyCommand(w: World, c: Command): World = c match
    case Command.Build(cell, tpe) =>
      val spec = Config.towerSpec(tpe)
      if canBuild(w, cell, tpe) then
        w.copy(
          towers = w.towers :+ Tower(cell, 0f, tpe),
          gold = w.gold - spec.cost
        )
      else w

  /** Whether a build command would be accepted — used by the renderer to colour
    * the placement preview, so preview and rule can never disagree.
    */
  def canBuild(w: World, cell: GridPos, tpe: String): Boolean =
    val spec = Config.towerSpec(tpe)
    w.gold >= spec.cost &&
      Grid.isBuildable(cell, w.level) &&
      !w.towers.exists(_.cell == cell)

  // -- environment rules -------------------------------------------------------

  private def biomeEnv(w: World): Option[Effect] =
    Config.biomes.find(_.id == w.run.biome).flatMap(_.environment)

  private def envParam(w: World, kind: String, key: String): Float =
    biomeEnv(w) match
      case Some(e) if e.kind == kind => e.params.getOrElse(key, 0f)
      case _                         => 0f

  private def killReward(w: World): Float =
    Config.KillReward + envParam(w, "recycling", "killGoldBonus")

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
          speed = spec.baseSpeed,
          tpe = spec.name,
          level = w.level,
          resist = spec.resist.layered(w.run.resistTheme)
        )
        w.copy(
          enemies = w.enemies :+ e,
          spawnQueue = w.spawnQueue.tail,
          spawnTimer = Waves.spec(w.level, w.wave).spawnInterval,
          nextId = w.nextId + 1
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
    * route, but gold and lives carry over (plus a small bonus). Winning the last
    * level sets [[World.won]] and freezes the sim.
    */
  private def completeLevel(w: World): World =
    val next = w.level + 1
    if next >= Config.levelCount then
      w.copy(
        won = true,
        enemies = Vector.empty,
        shots = Vector.empty,
        spawnQueue = Vector.empty,
        breakTimer = 0f
      )
    else
      w.copy(
        level = next,
        wave = 0,
        towers = Vector.empty,
        enemies = Vector.empty,
        shots = Vector.empty,
        spawnQueue = Vector.empty,
        spawnTimer = 0f,
        breakTimer = Waves.breakTime,
        gold = w.gold + Config.LevelBonus,
        hazards = Vector.empty
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
              if dx * dx + dy * dy <= a.radius * a.radius then
                a.kind match
                  case AuraKind.SpeedBoost => speedMult = math.max(speedMult, 1f + a.magnitude)
                  case AuraKind.Armor      => auraArmor = math.max(auraArmor, a.magnitude)
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
      else base.copy(
        enemies = alive,
        gold = base.gold + math.round(dead.size * killReward(base)),
        kills = base.kills + dead.size,
        hazards = base.hazards ++ crushedHazards(base, dead)
      )

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
    val advanced        = w.enemies.map(e => e.copy(progress = e.progress + e.effectiveSpeed * hazardMult(e, w.hazards) * dt))
    val (leaked, still) = advanced.partition(e => e.progress >= Grid.length(e.level))
    if leaked.isEmpty then w.copy(enemies = still)
    else
      val mult = math.max(0f, envParam(w, "recycling", "livesLossMult")) match
        case m if m <= 0f => 1f
        case m            => m
      val pending = w.leakDamage + leaked.size * mult
      val whole   = pending.toInt
      w.copy(
        enemies = still,
        lives = math.max(0, w.lives - whole),
        leaks = w.leaks + leaked.size,
        leakDamage = pending - whole
      )

  private def hazardMult(e: Enemy, hazards: Vector[Hazard]): Float =
    if hazards.isEmpty then 1f
    else
      val (ex, ey) = e.pos
      val factors = hazards.collect { case h if h.remaining > 0f =>
        val dx = h.x - ex; val dy = h.y - ey
        if dx * dx + dy * dy <= h.radius * h.radius then h.slowFactor else 1f
      }
      if factors.isEmpty then 1f else factors.min

  private def fireTowers(w: World, dt: Float): World =
    val fresh = Vector.newBuilder[Projectile]

    val towers = w.towers.map { t =>
      val cd = t.cooldown - dt
      if cd > 0f then t.copy(cooldown = cd)
      else
        val spec   = t.spec
        val range2 = spec.range * spec.range
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
            val dest = if spec.kind == DamageKind.Explosion then Some(e.pos) else None
            fresh += Projectile(
              t.cell.centerX,
              t.cell.centerY,
              e.id,
              spec.kind,
              spec.damage,
              spec.projectileSpeed,
              spec.splashRadius,
              dest
            )
            t.copy(cooldown = spec.fireInterval)
    }

    w.copy(towers = towers, shots = w.shots ++ fresh.result())

  /** One landing shot's worth of effect on a single enemy. Resistances are not
    * applied here — each target reads its own resist/armor when the hit lands.
    */
  private case class Hit(id: Long, kind: DamageKind, damage: Float, burn: Option[(Float, Float)], slow: Option[(Float, Float)])

  /** Moves each shot, converts the ones that land into hits, applies them, then
    * cashes in whatever died. Explosion shots detonate at their fixed point even
    * if the original target has already died.
    */
  private def moveProjectiles(w: World, dt: Float): World =
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

    val hits     = hs.result()
    val hitsById = hits.groupBy(_.id)
    val damaged  = w.enemies.map { e =>
      hitsById.get(e.id) match
        case None => e
        case Some(es) =>
          es.foldLeft(e) { (acc, h) =>
            val dmg = math.max(0f, h.damage * (1f - e.resist(h.kind)) * (1f - e.armor))
            var cur = acc.copy(hp = acc.hp - dmg)
            h.burn.foreach { case (dps, dur) =>
              val fr = e.resist(DamageKind.Fire)
              val du = dur * (1f - fr)
              if du > 0f then
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

    w.copy(
      shots = fs.result(),
      enemies = alive,
      gold = w.gold + math.round(dead.size * killReward(w)),
      kills = w.kills + dead.size,
      hazards = w.hazards ++ crushedHazards(w, dead)
    )

  private def resolveImpact(w: World, p: Projectile, at: (Float, Float)): Vector[Hit] =
    val (ix, iy) = at
    val targets =
      if p.splashRadius > 0f then
        val r2 = p.splashRadius * p.splashRadius
        w.enemies.filter { e =>
          val (ex, ey) = e.pos
          val dx       = ex - ix
          val dy       = ey - iy
          dx * dx + dy * dy <= r2
        }
      else w.enemies.filter(_.id == p.targetId)

    targets.map { e =>
      p.kind match
        case DamageKind.Kinetic   => Hit(e.id, DamageKind.Kinetic, p.damage, None, None)
        case DamageKind.Fire      => Hit(e.id, DamageKind.Fire, p.damage, Some((Config.BurnDps, Config.BurnDuration)), None)
        case DamageKind.Slow      => Hit(e.id, DamageKind.Slow, p.damage, None, Some((Config.SlowFactor, Config.SlowDuration)))
        case DamageKind.Splash    => Hit(e.id, DamageKind.Splash, p.damage, None, None)
        case DamageKind.Explosion => Hit(e.id, DamageKind.Explosion, p.damage, None, None)
    }
