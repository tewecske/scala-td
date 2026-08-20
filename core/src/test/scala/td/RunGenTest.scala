package td

class RunGenTest extends munit.FunSuite:

  test("same seed rolls the same run") {
    Seq(0x5EEDL, 1L, 42L, 0L, 123456789L).foreach { s =>
      assertEquals(RunGen.roll(s), RunGen.roll(s))
    }
  }

  test("different seeds spread across the real biomes") {
    val biomes = (1L to 50L).map(RunGen.roll(_).biome).toSet
    assert(biomes.size >= 2)
    assert(!biomes.contains("tutorial"))
  }

  test("tutorial is identical across seeds except the echoed seed") {
    assertEquals(RunGen.tutorial(1L).copy(seed = 0L), RunGen.tutorial(2L).copy(seed = 0L))
    assertEquals(RunGen.tutorial(7L).biome, "tutorial")
    assert(RunGen.tutorial(7L).twists.isEmpty)
  }

  test("resist theme always has distinct primary and secondary kinds") {
    (1L to 50L).foreach { s =>
      val rt = RunGen.roll(s).resistTheme
      assertNotEquals(rt.primary, rt.secondary)
    }
    assertNotEquals(RunGen.tutorial(1L).resistTheme.primary, RunGen.tutorial(1L).resistTheme.secondary)
  }

  test("roster is the chosen biome's roster and references existing enemies") {
    val enemyNames = Config.game.enemies.map(_.name).toSet
    (1L to 50L).foreach { s =>
      val run   = RunGen.roll(s)
      val biome = Config.biome(run.biome)
      assertEquals(run.roster, biome.roster)
      assert(run.roster.nonEmpty)
      run.roster.foreach(e => assert(enemyNames.contains(e)))
    }
  }

  test("twists resolve to existing Config.twists ids") {
    val twistIds = Config.twists.map(_.id).toSet
    (1L to 50L).foreach { s => RunGen.roll(s).twists.foreach(id => assert(twistIds.contains(id))) }
    assert(RunGen.roll(7L).twists.nonEmpty)
  }

  test("Sim.initial(seed) carries the rolled run; initial(run) uses it verbatim") {
    assertEquals(Sim.initial(42L).run, RunGen.roll(42L))
    val t = Sim.initial(RunGen.tutorial(9L))
    assertEquals(t.run.biome, "tutorial")
    assertEquals(t.run.seed, 9L)
  }
