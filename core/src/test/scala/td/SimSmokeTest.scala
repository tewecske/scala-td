package td

class SimSmokeTest extends munit.FunSuite:
  test("config loads and initial world is valid") {
    val w = Sim.initial()
    assertEquals(w.level, 0)
    assertEquals(w.gold, Config.StartGold)
    assertEquals(w.lives, Config.StartLives)
    assert(w.towers.isEmpty)
    assert(w.enemies.isEmpty)
  }
