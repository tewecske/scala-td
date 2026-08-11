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
      val moved    = moveEnemies(spawned, dt)
      val fired    = fireTowers(moved, dt)
      val resolved = moveProjectiles(fired, dt)
      advanceWave(resolved, dt)

  // -- input -----------------------------------------------------------------

  private def applyCommand(w: World, c: Command): World = c match
    case Command.Build(cell) =>
      val affordable = w.gold >= Config.TowerCost
      val free       = !w.towers.exists(_.cell == cell)
      if affordable && free && Grid.isBuildable(cell) then
        w.copy(
          towers = w.towers :+ Tower(cell, 0f),
          gold = w.gold - Config.TowerCost
        )
      else w

  /** Whether a build command would be accepted — used by the renderer to colour
    * the placement preview, so preview and rule can never disagree.
    */
  def canBuild(w: World, cell: GridPos): Boolean =
    w.gold >= Config.TowerCost &&
      Grid.isBuildable(cell) &&
      !w.towers.exists(_.cell == cell)

  // -- wave pacing -----------------------------------------------------------

  private def spawn(w: World, dt: Float): World =
    if w.inBreak || w.spawnQueue <= 0 then w
    else
      val t = w.spawnTimer - dt
      if t > 0f then w.copy(spawnTimer = t)
      else
        val e = Enemy(
          id = w.nextId,
          progress = 0f,
          hp = Waves.hp(w.wave),
          maxHp = Waves.hp(w.wave),
          speed = Waves.speed(w.wave)
        )
        w.copy(
          enemies = w.enemies :+ e,
          spawnQueue = w.spawnQueue - 1,
          spawnTimer = Waves.spawnInterval(w.wave),
          nextId = w.nextId + 1
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

  // -- movement --------------------------------------------------------------

  private def moveEnemies(w: World, dt: Float): World =
    val advanced        = w.enemies.map(e => e.copy(progress = e.progress + e.speed * dt))
    val (leaked, still) = advanced.partition(_.progress >= Grid.length)
    if leaked.isEmpty then w.copy(enemies = still)
    else w.copy(enemies = still, lives = math.max(0, w.lives - leaked.size))

  private def fireTowers(w: World, dt: Float): World =
    val range2 = Config.TowerRange * Config.TowerRange
    val fresh  = Vector.newBuilder[Projectile]

    val towers = w.towers.map { t =>
      val cd = t.cooldown - dt
      if cd > 0f then t.copy(cooldown = cd)
      else
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
            fresh += Projectile(t.cell.centerX, t.cell.centerY, e.id, Config.TowerDamage)
            t.copy(cooldown = Config.TowerFireInterval)
    }

    w.copy(towers = towers, shots = w.shots ++ fresh.result())

  /** Homes each shot at its target, applies the damage of any that land, then
    * cashes in whatever died.
    */
  private def moveProjectiles(w: World, dt: Float): World =
    val byId    = w.enemies.map(e => e.id -> e).toMap
    val stepLen = Config.ProjectileSpeed * dt

    val (flying, landed) = w.shots.partitionMap { p =>
      byId.get(p.targetId) match
        case None => Right(None) // target already dead: the shot fizzles
        case Some(e) =>
          val (ex, ey) = e.pos
          val dx       = ex - p.x
          val dy       = ey - p.y
          val d        = math.hypot(dx, dy).toFloat
          if d <= stepLen + Config.HitRadius then Right(Some(p.targetId -> p.damage))
          else Left(p.copy(x = p.x + dx / d * stepLen, y = p.y + dy / d * stepLen))
    }

    val damage  = landed.flatten.groupMapReduce(_._1)(_._2)(_ + _)
    val hit     = w.enemies.map(e => damage.get(e.id).fold(e)(d => e.copy(hp = e.hp - d)))
    val (dead, alive) = hit.partition(_.hp <= 0f)

    w.copy(
      shots = flying,
      enemies = alive,
      gold = w.gold + dead.size * Config.KillReward
    )
