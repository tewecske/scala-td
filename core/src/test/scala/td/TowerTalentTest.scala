package td

import munit.AfterEach

/** The tower talents: fire rate, crits, per-kind damage and shred. */
class TowerTalentTest extends TestBase:

  /** A level-one world with one Cuboid next to a sturdy Cardboard, under `talents`. */
  private def scene(talents: Vector[String], cfg: GameConfig = Config.game): World =
    Config.use(cfg)
    val e = Enemy(id = 1L, progress = 40f, hp = 1e6f, maxHp = 1e6f, speed = 0f, tpe = "Cardboard", level = 0,
      resist = Config.enemySpec("Cardboard").resist)
    val tower = Tower(cellNear(Grid.cellAt(e.pos._1, e.pos._2)), 0f, "Cuboid")
    Sim.initial(RunContext(seed = 5L, talents = talents)).copy(towers = Vector(tower), enemies = Vector(e),
      spawnQueue = Vector.empty, breakTimer = 999f)

  private def firstShot(w: World): (Projectile, Tower) =
    val next = Sim.step(w, Config.MaxStepDt, Vector.empty)
    (next.shots.head, next.towers.head)

  private def withTalent(id: String, kind: String, params: Map[String, Float]): GameConfig =
    Config.game.copy(talents = Config.game.talents :+ Talent(id, id, TalentLayer.Filler, 1, Vector.empty, Effect(kind, params)))

  override def afterEach(context: AfterEach): Unit =
    ConfigLoader.load().foreach(Config.use)
    super.afterEach(context)

  test("the fire-rate talent shortens the reload") {
    val (_, plain) = firstShot(scene(Vector.empty))
    val (_, quick) = firstShot(scene(Vector("filler-rate")))
    assertEqualsDouble(quick.cooldown.toDouble, plain.cooldown / 1.1, 1e-4)
  }

  test("a per-kind damage talent only boosts towers of that kind") {
    val (plain, _)   = firstShot(scene(Vector.empty))
    val (kinetic, _) = firstShot(scene(Vector("breaker")))
    val (fire, _)    = firstShot(scene(Vector("pyromancer")))
    assertEqualsDouble(kinetic.damage.toDouble, plain.damage * 1.25, 1e-3)
    assertEqualsDouble(fire.damage.toDouble, plain.damage.toDouble, 1e-6)
  }

  test("a certain crit multiplies the shot by the crit multiplier plus crit-damage talents") {
    val cfg = withTalent("always-crit", "critChance", Map("chance" -> 1f))
    val (plain, _) = firstShot(scene(Vector.empty))
    val (crit, _)  = firstShot(scene(Vector("always-crit"), cfg))
    assert(crit.crit)
    assertEqualsDouble(crit.damage.toDouble, plain.damage * Config.CritMult, 1e-3)
    val (sharp, _) = firstShot(scene(Vector("always-crit", "sharp-corners"), cfg))
    assertEqualsDouble(sharp.damage.toDouble, plain.damage * (Config.CritMult + 0.5), 1e-3)
  }

  test("crits are the same every time the same run is played") {
    def crits(seed: Long) =
      val w0 = scene(Vector("filler-crit")).copy(run = RunContext(seed = seed, talents = Vector("filler-crit")))
      Iterator.iterate(w0)(w => Sim.step(w, Config.MaxStepDt, Vector.empty)).take(2000)
        .flatMap(_.hitFeedback).map(_.crit).toVector
    val a = crits(11L)
    assertEquals(a, crits(11L))
    assert(a.contains(true) && a.contains(false))
  }

  test("shred lowers the hit kind's resistance a little per hit, down to its maximum") {
    val w0 = scene(Vector("wear-and-tear"))
    val e  = w0.enemies.head.copy(resist = Resistances(kinetic = 0.2f))
    def hit(w: World): World =
      val (x, y) = e.pos
      Sim.step(w.copy(shots = Vector(Projectile(x, y, e.id, DamageKind.Kinetic, 1f, 1e6f, 0f, None)), towers = Vector.empty),
        Config.MaxStepDt, Vector.empty)
    val once = hit(w0.copy(enemies = Vector(e)))
    assertEqualsDouble(once.enemies.head.resist.kinetic.toDouble, 0.2 - 0.03, 1e-5)
    assertEqualsDouble(once.enemies.head.resist.fire.toDouble, 0.0, 1e-6)
    val many = Iterator.iterate(once)(hit).drop(30).next()
    // Cardboard starts at 0 kinetic in the plain biome, so the floor is 0 - 30%
    assertEqualsDouble(many.enemies.head.resist.kinetic.toDouble, -0.3, 1e-5)
  }
