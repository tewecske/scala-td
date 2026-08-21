package td

class TwistTest extends munit.FunSuite:

  private val towerCell = GridPos(0, 16)

  private def startEnemy(id: Long, hp: Float = 100f, tpe: String = "Cardboard"): Enemy =
    Enemy(id = id, progress = 0f, hp = hp, maxHp = 100f, speed = 0f, tpe = tpe,
      level = 0, resist = Config.enemySpec(tpe).resist)

  private def worldWith(twists: Vector[String], towers: Vector[Tower] = Vector.empty, enemies: Vector[Enemy] = Vector.empty): World =
    Sim.initial(RunContext(seed = 1L, twists = twists))
      .copy(towers = towers, enemies = enemies, spawnQueue = Vector.empty, breakTimer = 999f)

  private def shotAt(e: Enemy, kind: DamageKind, damage: Float = 10f): Projectile =
    val (x, y) = e.pos
    Projectile(x, y, e.id, kind, damage, 1e6f, 0f, None)

  test("brittle: slowed enemies take bonus kinetic damage") {
    val e     = startEnemy(1L).copy(slowUntil = 5f, slowFactor = 0.5f)
    val shot  = shotAt(e, DamageKind.Kinetic)
    val withBrittle = Sim.step(worldWith(Vector("brittle"), enemies = Vector(e)).copy(shots = Vector(shot)), Config.MaxStepDt, Vector.empty)
    val plain       = Sim.step(worldWith(Vector.empty, enemies = Vector(e)).copy(shots = Vector(shot)), Config.MaxStepDt, Vector.empty)
    assertEquals(withBrittle.enemies.head.hp, 85f)
    assertEquals(plain.enemies.head.hp, 90f)
  }

  test("brittle shortens frost slow duration") {
    val e     = startEnemy(1L)
    val t     = Tower(towerCell, 0f, "Frost")
    val fired = Sim.step(worldWith(Vector("brittle"), Vector(t), Vector(e)), Config.MaxStepDt, Vector.empty)
    assert(fired.shots.nonEmpty)
    assertEquals(fired.shots.head.slow, Some((Config.SlowFactor, Config.SlowDuration * 0.5f)))
  }

  test("backdraft bakes faster, non-stacking burns into fire shots") {
    val e     = startEnemy(1L)
    val t     = Tower(towerCell, 0f, "Fire")
    val fired = Sim.step(worldWith(Vector("backdraft"), Vector(t), Vector(e)), Config.MaxStepDt, Vector.empty)
    assert(fired.shots.nonEmpty)
    assertEquals(fired.shots.head.burnTickRate, 2f)
    assert(fired.shots.head.noBurnStack)
  }

  test("backdraft makes burns tick faster") {
    val e    = startEnemy(1L)
    val p    = shotAt(e, DamageKind.Fire).copy(burnTickRate = 2f)
    val w    = worldWith(Vector("backdraft"), enemies = Vector(e)).copy(shots = Vector(p))
    val step = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assertEquals(step.enemies.head.burnDps, Config.BurnDps * 2f)
  }

  test("backdraft burns no longer stack") {
    val e       = startEnemy(1L).copy(burnDps = 6f, burnUntil = 2f)
    val stacked = Sim.step(worldWith(Vector.empty, enemies = Vector(e)).copy(shots = Vector(shotAt(e, DamageKind.Fire))), Config.MaxStepDt, Vector.empty)
    val plain   = shotAt(e, DamageKind.Fire).copy(burnTickRate = 2f, noBurnStack = true)
    val capped  = Sim.step(worldWith(Vector("backdraft"), enemies = Vector(e)).copy(shots = Vector(plain)), Config.MaxStepDt, Vector.empty)
    assertEquals(stacked.enemies.head.burnUntil, Config.BurnDuration)
    assert(capped.enemies.head.burnUntil < Config.BurnDuration)
  }

  test("shaped charge shrinks radius and pierces armor") {
    val e     = startEnemy(1L)
    val t     = Tower(towerCell, 0f, "Bombard")
    val fired = Sim.step(worldWith(Vector("shaped-charge"), Vector(t), Vector(e)), Config.MaxStepDt, Vector.empty)
    assert(fired.shots.nonEmpty)
    val p = fired.shots.head
    assert(p.splashRadius < Config.towerSpec("Bombard").splashRadius)
    assert(p.splashRadius > 0f)
    assert(p.armorPierce)
  }

  test("shaped charge direct hits ignore armor") {
    val e     = startEnemy(1L).copy(armor = 0.5f)
    val (x, y) = e.pos
    val pierce = Projectile(x, y, e.id, DamageKind.Explosion, 10f, 1e6f, 0f, Some((x, y)), armorPierce = true)
    val plain  = Projectile(x, y, e.id, DamageKind.Explosion, 10f, 1e6f, 0f, Some((x, y)))
    val withPierce = Sim.step(worldWith(Vector("shaped-charge"), enemies = Vector(e)).copy(shots = Vector(pierce)), Config.MaxStepDt, Vector.empty)
    val noPierce   = Sim.step(worldWith(Vector.empty, enemies = Vector(e)).copy(shots = Vector(plain)), Config.MaxStepDt, Vector.empty)
    assertEquals(withPierce.enemies.head.hp, 90f)
    assertEquals(noPierce.enemies.head.hp, 95f)
  }

  test("ricochet slows fire rate and every 3rd shot bounces") {
    val e     = startEnemy(1L)
    val t     = Tower(towerCell, 0f, "Basic", shotsFired = 2)
    val fired = Sim.step(worldWith(Vector("ricochet"), Vector(t), Vector(e)), Config.MaxStepDt, Vector.empty)
    assert(fired.shots.nonEmpty)
    assert(fired.shots.head.bounce)
    assert(fired.towers.head.cooldown > Config.towerSpec("Basic").fireInterval)
  }

  test("ricochet bounce hits a second target") {
    val e1 = startEnemy(1L)
    val e2 = startEnemy(2L).copy(progress = 1f)
    val (x, y) = e1.pos
    val p = Projectile(x, y, e1.id, DamageKind.Kinetic, 10f, 1e6f, 0f, None, bounce = true, bounceRadius = 2f * Config.CellPx)
    val step = Sim.step(worldWith(Vector("ricochet"), enemies = Vector(e1, e2)).copy(shots = Vector(p)), Config.MaxStepDt, Vector.empty)
    assertEquals(step.enemies.find(_.id == 1L).get.hp, 90f)
    assertEquals(step.enemies.find(_.id == 2L).get.hp, 90f)
  }

  test("a twist on a kind the player didn't bring has no effect") {
    val e     = startEnemy(1L)
    val t     = Tower(towerCell, 0f, "Frost")
    val fired = Sim.step(worldWith(Vector("ricochet"), Vector(t), Vector(e)), Config.MaxStepDt, Vector.empty)
    assert(fired.shots.nonEmpty)
    assert(!fired.shots.head.bounce)
    assertEquals(fired.towers.head.cooldown, Config.towerSpec("Frost").fireInterval)
  }
