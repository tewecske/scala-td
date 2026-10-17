package td

import munit.FunSuite

class EnemyMechanicsTest extends FunSuite:

  test("negative resist is a weakness: amplifies fire and survives theme layering") {
    // Crate is weak to fire; in the plain front-lawn biome (no fire theme) the
    // negative resist is preserved through layering instead of being clamped to 0.
    val biome = Config.biome("front-lawn")
    val got   = Config.enemySpec("Crate").resist.layered(biome.resistTheme)
    assertEquals(got.fire, -0.5f)
    val dmg = 10f * (1f - got.fire)
    assert(dmg > 10f) // takes more damage than an unresisted hit
  }

  test("per-enemy kill reward: a boss pays more than fodder") {
    // Use the plain front-lawn biome so no environment bonus (e.g. recycling)
    // skews the per-kill gold away from the base kill reward.
    val run       = RunGen.roll(42L).copy(biome = "front-lawn", resistTheme = Config.biome("front-lawn").resistTheme)
    val crateSpec = Config.enemySpec("Crate")
    val cardSpec  = Config.enemySpec("Cardboard")
    val crate = Enemy(id = 1L, progress = 0f, hp = 0.5f, maxHp = 300f, speed = 14f, tpe = "Crate", level = 0, resist = crateSpec.resist)
    val card  = Enemy(id = 2L, progress = 0f, hp = 0.5f, maxHp = 22f, speed = 42f, tpe = "Cardboard", level = 0, resist = cardSpec.resist)
    val w = Sim.initial(run).copy(enemies = Vector(crate, card), spawnQueue = Vector.empty, breakTimer = 999f, gold = 0)
    val burning = w.copy(enemies = w.enemies.map(_.copy(burnDps = 100f, burnUntil = 10f)))
    val stepped = Sim.step(burning, Config.MaxStepDt, Vector.empty)
    val base     = Config.KillReward
    val expected = math.round(base * crateSpec.killReward) + math.round(base * cardSpec.killReward)
    assertEquals(stepped.gold, expected)
    assert(stepped.gold > math.round(base * cardSpec.killReward))
  }

  test("speedMults are rolled once per run and are deterministic") {
    val shipped = Config.game
    Config.use(ConfigEdits.updateEnemy(shipped, "Paper Cut")(_.copy(speedVariance = 0.35f)))
    try
      val a = RunGen.roll(123L)
      val b = RunGen.roll(123L)
      assertEquals(a.speedMults, b.speedMults)
      assert(a.speedMults.contains("Paper Cut"))
      val m = a.speedMults("Paper Cut")
      assert(m >= 1f && m <= 1.35f)
    finally Config.use(shipped)
  }

  test("tutorial applies no speed variance (identical every run)") {
    assertEquals(RunGen.tutorial(1L).speedMults, Map.empty)
    assertEquals(RunGen.tutorial(9L).speedMults, Map.empty)
  }
