package td

class DevTest extends TestBase:
  test("GrantGold adds currency without spending it") {
    var w = Sim.initial(RunContext.DefaultSeed)
    val before = w.gold
    w = Sim.step(w, Config.MaxStepDt, Vector(Command.GrantGold(250)))
    assertEquals(w.gold, before + 250)
  }

  test("UnlockTowers appends every tower to the run loadout") {
    var w = Sim.initial(RunContext.DefaultSeed)
    val locked = Config.Loadout.locked.toSet
    assert(locked.nonEmpty, "fixture expects some locked towers")
    w = Sim.step(w, Config.MaxStepDt, Vector(Command.UnlockTowers()))
    locked.foreach(t => assert(w.run.loadout.contains(t), s"$t should be unlocked"))
    val pool = Config.LoadoutPool(w.run.loadout).map(_.name).toSet
    locked.foreach(t => assert(pool.contains(t), s"$t should be buildable"))
  }

  test("grant commands are deterministic and pure") {
    val mk = () => Sim.step(Sim.initial(RunContext.DefaultSeed), Config.MaxStepDt,
      Vector(Command.GrantGold(100), Command.UnlockTowers()))
    assertEquals(mk(), mk())
  }
