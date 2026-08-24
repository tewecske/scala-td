package td

class SimSmokeTest extends TestBase:
  test("config loads and initial world is valid") {
    val w = Sim.initial(RunContext.DefaultSeed)
    assertEquals(w.level, 0)
    assertEquals(w.gold, Config.StartGold)
    assertEquals(w.lives, Config.StartLives)
    assertEquals(w.run.seed, RunContext.DefaultSeed)
    assert(w.towers.isEmpty)
    assert(w.enemies.isEmpty)
  }

  test("initial world has zero kills and leaks") {
    val w = Sim.initial(RunContext.DefaultSeed)
    assertEquals(w.kills, 0)
    assertEquals(w.leaks, 0)
  }

  test("leaks accumulate with no towers; kills stay zero") {
    var w = Sim.initial(RunGen.tutorial(1L))
    var guard = 0
    while w.leaks == 0 && guard < 100000 do
      w = Sim.step(w, Config.MaxStepDt, Vector.empty)
      guard += 1
    assert(w.leaks > 0)
    assertEquals(w.kills, 0)
    assert(w.lives < Config.StartLives)
  }
