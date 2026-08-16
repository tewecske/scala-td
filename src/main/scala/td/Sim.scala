package td

/** The entire game rule set, as a pure function of (state, time, input).
  *
  * Nothing here touches libGDX, so the simulation can be stepped headlessly.
  */
object Sim:

  def initial(seed: Long = 1L): World = World(
    towers = Vector.empty,
    enemies = Vector.empty,
    shots = Vector.empty,
    gold = Config.StartGold,
    lives = Config.StartLives,
    wave = 0,
    spawnQueue = 0,
    spawnTimer = 0f,
    breakTimer = Waves.BreakTime, // grace period before the first wave
    nextId = 1L,
    rng = seed
  )

  def step(w0: World, dt: Float, cmds: Vector[Command]): World =
    if w0.isOver then w0
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
      val affordable = w.gold >= tpe.cost
      val free       = !w.towers.exists(_.cell == cell)
      if affordable && free && Grid.isBuildable(cell) then
        w.copy(
          towers = w.towers :+ Tower(cell, 0f, tpe),
          gold = w.gold - tpe.cost
        )
      else w

  /** Whether a build command would be accepted — used by the renderer to colour
    * the placement preview, so preview and rule can never disagree.
    */
  def canBuild(w: World, cell: GridPos, tpe: TowerType): Boolean =
    w.gold >= tpe.cost &&
      Grid.isBuildable(cell) &&
      !w.towers.exists(_.cell == cell)

  // -- wave pacing -----------------------------------------------------------

  /** SplitMix64: small, pure, deterministic. Returns (next state, value). */
  private def rngNext(rng: Long): (Long, Long) =
    var z = rng + 0x9E3779B97F4A7C15L
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL
    z = (z ^ (z >>> 31))
    (z, z)

  /** Weighted pick from a (type, weight) table using the seeded rng. Isolated
    * here so a later "levels" task can swap the table without touching anything
    * else.
    */
  private def pickType(rng: Long, table: Vector[(EnemyType, Int)]): (EnemyType, Long) =
    val (next, r) = rngNext(rng)
    val total     = table.map(_._2).sum
    val pick      = math.floorMod(r, total.toLong).toInt
    var acc       = pick
    var chosen    = table.head._1
    var i         = 0
    while i < table.size do
      val (t, w) = table(i)
      if acc < w then
        chosen = t
        i = table.size
      else
        acc -= w
        i += 1
    (chosen, next)

  private def spawn(w: World, dt: Float): World =
    if w.inBreak || w.spawnQueue <= 0 then w
    else
      val t = w.spawnTimer - dt
      if t > 0f then w.copy(spawnTimer = t)
      else
        val (tpe, rng) = pickType(w.rng, Waves.enemyTypes(w.wave))
        val hp         = tpe.baseHp * Waves.hpScale(w.wave)
        val e          = Enemy(
          id = w.nextId,
          progress = 0f,
          hp = hp,
          maxHp = hp,
          speed = tpe.baseSpeed,
          tpe = tpe
        )
        w.copy(
          enemies = w.enemies :+ e,
          spawnQueue = w.spawnQueue - 1,
          spawnTimer = Waves.spawnInterval(w.wave),
          nextId = w.nextId + 1,
          rng = rng
        )

  /** Counts down the break and starts the next wave; once a wave is fully
    * spawned and cleared, opens the next break.
    */
  private def advanceWave(w: World, dt: Float): World =
    if w.inBreak then
      val t = w.breakTimer - dt
      if t > 0f then w.copy(breakTimer = t)
      else
        val next = w.wave + 1
        w.copy(
          breakTimer = 0f,
          wave = next,
          spawnQueue = Waves.count(next),
          spawnTimer = 0f // first enemy of the wave appears immediately
        )
    else if w.spawnQueue == 0 && w.enemies.isEmpty then
      w.copy(breakTimer = Waves.BreakTime)
    else w

  // -- auras ------------------------------------------------------------------

  /** Buffs each enemy from nearby aura carriers. Only the strongest source of
    * each buff kind counts, so stacking commanders can never balloon speed.
    */
  private def applyAuras(w: World): World =
    val carriers = w.enemies.filter(_.tpe.aura.isDefined)
    if carriers.isEmpty then w
    else
      w.copy(enemies = w.enemies.map { e =>
        var speedMult = 1f
        var armor     = 0f
        carriers.foreach { src =>
          if src.id != e.id then
            src.tpe.aura.foreach { a =>
              val (sx, sy) = src.pos
              val (ex, ey) = e.pos
              val dx       = sx - ex
              val dy       = sy - ey
              if dx * dx + dy * dy <= a.radius * a.radius then
                a.kind match
                  case AuraKind.SpeedBoost => speedMult = math.max(speedMult, 1f + a.magnitude)
                  case AuraKind.Armor      => armor = math.max(armor, a.magnitude)
            }
        }
        e.copy(speedMult = speedMult, armor = armor)
      })

  // -- movement --------------------------------------------------------------

  /** Burns tick down hp; slow timers count down. Any enemy that burns to death
    * is cashed in here.
    */
  private def tickEffects(w: World, dt: Float): World =
    if w.enemies.isEmpty then w
    else
      val updated = w.enemies.map { e =>
        val burned =
          if e.burning then e.copy(hp = e.hp - e.burnDps * dt, burnUntil = e.burnUntil - dt)
          else e
        if burned.slowed then burned.copy(slowUntil = burned.slowUntil - dt) else burned
      }
      val (dead, alive) = updated.partition(_.hp <= 0f)
      if dead.isEmpty then w.copy(enemies = alive)
      else w.copy(enemies = alive, gold = w.gold + dead.size * Config.KillReward)

  private def moveEnemies(w: World, dt: Float): World =
    val advanced        = w.enemies.map(e => e.copy(progress = e.progress + e.effectiveSpeed * dt))
    val (leaked, still) = advanced.partition(_.progress >= Grid.length)
    if leaked.isEmpty then w.copy(enemies = still)
    else w.copy(enemies = still, lives = math.max(0, w.lives - leaked.size))

  private def fireTowers(w: World, dt: Float): World =
    val fresh = Vector.newBuilder[Projectile]

    val towers = w.towers.map { t =>
      val cd = t.cooldown - dt
      if cd > 0f then t.copy(cooldown = cd)
      else
        val range2 = t.tpe.range * t.tpe.range
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
            val dest = if t.tpe.kind == DamageKind.Explosion then Some(e.pos) else None
            fresh += Projectile(
              t.cell.centerX,
              t.cell.centerY,
              e.id,
              t.tpe.kind,
              t.tpe.damage,
              t.tpe.projectileSpeed,
              t.tpe.splashRadius,
              dest
            )
            t.copy(cooldown = t.tpe.fireInterval)
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
      gold = w.gold + dead.size * Config.KillReward
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
