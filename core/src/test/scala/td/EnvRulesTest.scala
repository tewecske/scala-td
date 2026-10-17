package td

class EnvRulesTest extends TestBase:

  /** A run in `biomeId`. Its rules apply from the second level on (`level = 1`);
    * the first level is always in the starter biome.
    */
  private def runFor(biomeId: String): RunContext =
    val b = Config.biome(biomeId)
    RunContext(seed = 7L, biome = b.id, resistTheme = b.resistTheme, roster = b.roster)

  /** The biome each rule used to ship on; the shipped biomes have none now, so each
    * rule's test puts it back (with its default values) for the length of the test.
    */
  private val RuleOf = Map("cube-farm" -> "openFloorPlan", "shipping-yard" -> "crushed", "cardboard-wastes" -> "recycling")

  private def withRule[A](biomeId: String)(body: => A): A =
    val shipped = Config.game
    val rule    = ConfigEdits.EnvironmentRules.find(r => RuleOf.get(biomeId).contains(r.kind))
    Config.use(ConfigEdits.updateBiome(shipped, biomeId)(_.copy(environment = rule)))
    try body
    finally Config.use(shipped)

  private def enemy(run: RunContext, tpe: String, id: Long, progress: Float = 0f, hp: Float = 100f): Enemy =
    val spec = Config.enemySpec(tpe)
    Enemy(id = id, progress = progress, hp = hp, maxHp = 100f, speed = spec.baseSpeed * Config.CellPx,
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

  test("every run's first level is in the plain starter biome, which is never rolled") {
    val starter = Config.starterBiome.get
    val plain   = Config.biome(starter)
    assert(plain.environment.isEmpty)
    assertEquals(plain.resistTheme.primaryAmount, 0f)
    assertEquals(plain.resistTheme.secondaryAmount, 0f)
    (1L to 200L).foreach { seed =>
      val run = RunGen.roll(seed)
      assert(run.biome != starter && run.biome != "tutorial", run.biome)
      val w = Sim.initial(run)
      assertEquals(w.biome, starter)
      assertEquals(w.resistTheme, plain.resistTheme)
      assertEquals(w.copy(level = 1).biome, run.biome)
      assertEquals(w.copy(level = 1).resistTheme, run.resistTheme)
    }
    val tut = Sim.initial(RunGen.tutorial())
    assertEquals(tut.biome, "tutorial")
  }

  test("the config rejects a starter biome with an environment rule or resistances") {
    val text = scala.io.Source.fromResource("config.json").mkString
    val withEnv = text.replace("\"starter\": true", "\"starter\": true, \"environment\": {\"kind\": \"crushed\", \"params\": {}}")
      .replaceFirst("(\"id\": \"front-lawn\"[\\s\\S]*?)\"environment\": null,", "$1")
    assert(ConfigLoader.parse(withEnv).left.exists(_.contains("environment")), ConfigLoader.parse(withEnv))
    val resist = text.replaceFirst("(\"id\": \"front-lawn\"[\\s\\S]*?\"primaryAmount\": )0\\.0", "$10.2")
    assert(ConfigLoader.parse(resist).left.exists(_.contains("resistances")), ConfigLoader.parse(resist))
  }

  test("spawned enemies carry the run's layered resist") {
    val run = RunGen.roll(123L)
    var w = Sim.initial(run).copy(level = 1, breakTimer = 0f)
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
    withRule("cube-farm") {
      val run = runFor("cube-farm")
      val e1  = enemy(run, "Cardboard", 1L)
      val e2  = enemy(run, "Cardboard", 2L)
      val w   = Sim.initial(run).copy(level = 1, enemies = Vector(e1, e2), spawnQueue = Vector.empty, breakTimer = 999f)
      val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
      assert(stepped.enemies.forall(_.armor > 0f))
      val per = Config.biome("cube-farm").environment.get.params("armorPerNeighbor")
      assertEquals(stepped.enemies.head.armor, per)
    }
  }

  test("crushed: a dying heavy leaves a temporary hazard") {
    withRule("shipping-yard") {
      val run   = runFor("shipping-yard")
      assert(Config.enemySpec("Brick").heavy)
      val dying = enemy(run, "Brick", 1L).copy(hp = 1f, burnDps = 100f, burnUntil = 10f)
      val w     = Sim.initial(run).copy(level = 1, enemies = Vector(dying), spawnQueue = Vector.empty, breakTimer = 999f)
      val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
      assert(stepped.enemies.isEmpty)
      assert(stepped.hazards.nonEmpty)
      assert(stepped.hazards.head.remaining > 0f)
    }
  }

  test("crushed: a dying light enemy leaves no hazard") {
    withRule("shipping-yard") {
      val run   = runFor("shipping-yard")
      assert(!Config.enemySpec("Ember").heavy)
      val dying = enemy(run, "Ember", 1L).copy(hp = 1f, burnDps = 100f, burnUntil = 10f)
      val w     = Sim.initial(run).copy(level = 1, enemies = Vector(dying), spawnQueue = Vector.empty, breakTimer = 999f)
      assert(Sim.step(w, Config.MaxStepDt, Vector.empty).hazards.isEmpty)
    }
  }

  test("recycling: leaks cost fewer lives") {
    withRule("cardboard-wastes") {
      val run = runFor("cardboard-wastes")
      val len = Config.withContext(run.biome, 1)(Grid.length(0))
      val e   = enemy(run, "Paper Cut", 1L, progress = len - 1f).copy(speed = 100f)
      val w   = Sim.initial(run).copy(level = 1, enemies = Vector(e), spawnQueue = Vector.empty, breakTimer = 999f)
      val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
      assertEquals(stepped.leaks, 1)
      assertEquals(stepped.lives, w.lives)
      assertEquals(stepped.leakDamage, 0.5f)
    }
  }

  test("recycling: swarms are larger") {
    withRule("cardboard-wastes") {
      def waveQueueSize(biomeId: String, level: Int): Int =
        val w = Sim.initial(runFor(biomeId)).copy(level = level, breakTimer = Config.MaxStepDt)
        Sim.step(w, Config.MaxStepDt, Vector.empty).spawnQueue.size
      assertEquals(waveQueueSize("tutorial", 0), 6)
      // Recycling scales each group of the second level's first wave by swarmSizeMult
      // (the first level is always in the plain starter biome).
      val mult     = Config.game.biomes.find(_.id == "cardboard-wastes").flatMap(_.environment).get.params("swarmSizeMult")
      val wave     = Config.game.levels(1).waves.head
      val expected = wave.composition.map((_, n) => math.round(n * mult)).sum
      assert(expected > wave.count)
      assertEquals(waveQueueSize("cardboard-wastes", 1), expected)
    }
  }
