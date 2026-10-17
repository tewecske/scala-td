package td

import munit.AfterEach

/** Per-tower targeting and hit effects (burn, slow, landing spot, puddle bonus). */
class TowerBehaviourTest extends TestBase:

  override def afterEach(context: AfterEach): Unit =
    ConfigLoader.load().foreach(Config.use)
    super.afterEach(context)

  private def withTower(name: String)(f: TowerSpec => TowerSpec): Unit =
    Config.use(ConfigEdits.updateTower(Config.game, name)(f))

  /** Two Cardboards next to each other in front of a Cuboid: one further along, one weaker. */
  private def scene: World =
    val ahead  = Enemy(id = 1L, progress = 44f, hp = 40f, maxHp = 40f, speed = 0f, tpe = "Cardboard", level = 0)
    val weaker = Enemy(id = 2L, progress = 40f, hp = 5f, maxHp = 40f, speed = 0f, tpe = "Cardboard", level = 0)
    val tower  = Tower(cellNear(Grid.cellAt(ahead.pos._1, ahead.pos._2)), 0f, "Cuboid")
    Sim.initial(RunContext(seed = 1L)).copy(towers = Vector(tower), enemies = Vector(ahead, weaker),
      spawnQueue = Vector.empty, breakTimer = 999f)

  test("towers shoot the enemy furthest along by default, the weakest one when set to") {
    assertEquals(Sim.step(scene, Config.MaxStepDt, Vector.empty).shots.head.targetId, 1L)
    withTower("Cuboid")(_.copy(targeting = Targeting.Weakest))
    assertEquals(Sim.step(scene, Config.MaxStepDt, Vector.empty).shots.head.targetId, 2L)
  }

  test("burn, slow, landing spot and puddle bonus come from the tower, not its damage type") {
    withTower("Cuboid")(_.copy(burnDps = 4f, burnDuration = 2f, slowFactor = 0.7f, slowDuration = 1f,
      groundTarget = true, puddleMult = 2f))
    val shot = Sim.step(scene, Config.MaxStepDt, Vector.empty).shots.head
    assertEquals(shot.kind, DamageKind.Kinetic)
    assertEquals(shot.burn, Some((4f, 2f)))
    assertEquals(shot.slow, Some((0.7f, 1f)))
    assertEquals(shot.puddleMult, 2f)
    assert(!shot.homes)
  }

  test("a config from before per-tower effects gets them from the old damage-type rules") {
    val text = ConfigWriter.toJson(Config.game)
      .replaceAll("\"(targeting|burnDps|burnDuration|slowFactor|slowDuration|groundTarget|puddleMult)\": [^,\\n]+,?", "")
    val old  = ConfigLoader.parse(text).fold(e => fail(e), identity)
    def tower(n: String) = old.towers.find(_.name == n).get
    assert(tower("Matchbox").burns && !tower("Cuboid").burns)
    assert(tower("Ice Cube").slows && tower("Ice Cube").slowFactor == 0.5f)
    assert(tower("Jack-in-the-Box").groundTarget && !tower("Cuboid").groundTarget)
    assertEquals(tower("Static Box").puddleMult, 1.5f)
    assertEquals(tower("Cuboid").targeting, Targeting.First)
  }
