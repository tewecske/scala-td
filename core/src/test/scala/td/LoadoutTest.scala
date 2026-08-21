package td

import munit.FunSuite

class LoadoutTest extends FunSuite:

  private def cell = GridPos(0, 0)

  test("canBuild rejects a tower not in a non-empty loadout") {
    val w = Sim.initial(RunContext(seed = 1L, loadout = Vector("Basic")))
    assert(Sim.canBuild(w, cell, "Basic"))
    assert(!Sim.canBuild(w, cell, "Cannon"))
  }

  test("empty loadout is unrestricted (back-compat for tests/bot)") {
    val w = Sim.initial(RunContext.DefaultSeed)
    assert(Sim.canBuild(w, cell, "Cannon"))
  }

  test("loadout gating is independent of buildability/occupancy") {
    val w = Sim.initial(RunContext(seed = 1L, loadout = Vector("Basic")))
    assert(!Sim.canBuild(w, cell, "Bombard"))
    val built = w.copy(towers = Vector(Tower(cell, 0f, "Basic")))
    assert(!Sim.canBuild(built, cell, "Basic"))
  }
