package td

class EnvRulesTest extends munit.FunSuite:

  private def runFor(biomeId: String): RunContext =
    val b = Config.biome(biomeId)
    RunContext(seed = 7L, biome = b.id, resistTheme = b.resistTheme, roster = b.roster)

  private def enemy(run: RunContext, tpe: String, id: Long, progress: Float = 0f, hp: Float = 100f): Enemy =
    val spec = Config.enemySpec(tpe)
    Enemy(id = id, progress = progress, hp = hp, maxHp = 100f, speed = spec.baseSpeed,
      tpe = tpe, level = 0, resist = spec.resist.layered(run.resistTheme))

  test("resist theme layers additively and leaves other kinds untouched") {
    val theme = ResistTheme(DamageKind.Kinetic, 0.5f, DamageKind.Fire, 0.25f)
    val base  = Resistances(kinetic = 0.35f, fire = 0.6f, slow = 0.7f)
    val got   = base.layered(theme)
    assertEquals(got.kinetic, 0.85f)
    assertEquals(got.fire, 0.85f)
    assertEquals(got.slow, 0.7f)
    assertEquals(got.splash, 0f)
    assertEquals(got.explosion, 0f)
  }

  test("resist theme clamps combined resist at 1.0 (immune)") {
    val got = Resistances(kinetic = 0.5f).layered(ResistTheme(DamageKind.Kinetic, 0.9f, DamageKind.Fire, 0.1f))
    assertEquals(got.kinetic, 1f)
  }

  test("spawned enemies carry the run's layered resist") {
    val run = RunGen.roll(123L)
    var w = Sim.initial(run).copy(breakTimer = 0f)
    var guard = 0
    while w.enemies.isEmpty && guard < 100000 do
      w = Sim.step(w, Config.MaxStepDt, Vector.empty)
      guard += 1
    assert(w.enemies.nonEmpty)
    val e = w.enemies.head
    val base = e.spec.resist
    assertEquals(e.resist.apply(run.resistTheme.primary),
      math.min(1f, base.apply(run.resistTheme.primary) + run.resistTheme.primaryAmount))
    assertEquals(e.resist.apply(run.resistTheme.secondary),
      math.min(1f, base.apply(run.resistTheme.secondary) + run.resistTheme.secondaryAmount))
  }

  test("openFloorPlan grants armor while enemies cluster") {
    val run = runFor("cube-farm")
    val e1  = enemy(run, "Cardboard", 1L)
    val e2  = enemy(run, "Cardboard", 2L)
    val w   = Sim.initial(run).copy(enemies = Vector(e1, e2), spawnQueue = Vector.empty, breakTimer = 999f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assert(stepped.enemies.forall(_.armor > 0f))
    val per = Config.biome("cube-farm").environment.get.params("armorPerNeighbor")
    assertEquals(stepped.enemies.head.armor, per)
  }

  test("crushed: a dying heavy leaves a temporary hazard") {
    val run   = runFor("shipping-yard")
    assert(Config.enemySpec("Brick").heavy)
    val dying = enemy(run, "Brick", 1L).copy(hp = 1f, burnDps = 100f, burnUntil = 10f)
    val w     = Sim.initial(run).copy(enemies = Vector(dying), spawnQueue = Vector.empty, breakTimer = 999f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assert(stepped.enemies.isEmpty)
    assert(stepped.hazards.nonEmpty)
    assert(stepped.hazards.head.remaining > 0f)
  }

  test("crushed: a dying light enemy leaves no hazard") {
    val run   = runFor("shipping-yard")
    assert(!Config.enemySpec("Ember").heavy)
    val dying = enemy(run, "Ember", 1L).copy(hp = 1f, burnDps = 100f, burnUntil = 10f)
    val w     = Sim.initial(run).copy(enemies = Vector(dying), spawnQueue = Vector.empty, breakTimer = 999f)
    assert(Sim.step(w, Config.MaxStepDt, Vector.empty).hazards.isEmpty)
  }

  test("recycling: leaks cost fewer lives") {
    val run = runFor("cardboard-wastes")
    val len = Grid.length(0)
    val e   = enemy(run, "Paper Cut", 1L, progress = len - 1f).copy(speed = 100f)
    val w   = Sim.initial(run).copy(enemies = Vector(e), spawnQueue = Vector.empty, breakTimer = 999f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assertEquals(stepped.leaks, 1)
    assertEquals(stepped.lives, w.lives)
    assertEquals(stepped.leakDamage, 0.5f)
  }

  test("recycling: swarms are larger") {
    def waveQueueSize(biomeId: String): Int =
      val w = Sim.initial(runFor(biomeId)).copy(breakTimer = Config.MaxStepDt)
      Sim.step(w, Config.MaxStepDt, Vector.empty).spawnQueue.size
    assertEquals(waveQueueSize("tutorial"), 8)
    assertEquals(waveQueueSize("cardboard-wastes"), 11)
  }
