package td

import munit.FunSuite

class LoadoutTest extends FunSuite:

  private def cell = GridPos(0, 0)

  test("canBuild rejects a tower not in a non-empty loadout") {
    val w = Sim.initial(RunContext(seed = 1L, loadout = Vector("Cuboid")))
    assert(Sim.canBuild(w, cell, "Cuboid"))
    assert(!Sim.canBuild(w, cell, "Hole Punch"))
  }

  test("empty loadout is unrestricted (back-compat for tests/bot)") {
    val w = Sim.initial(RunContext.DefaultSeed)
    assert(Sim.canBuild(w, cell, "Hole Punch"))
  }

  test("loadout gating is independent of buildability/occupancy") {
    val w = Sim.initial(RunContext(seed = 1L, loadout = Vector("Cuboid")))
    assert(!Sim.canBuild(w, cell, "Jack-in-the-Box"))
    val built = w.copy(towers = Vector(Tower(cell, 0f, "Cuboid")))
    assert(!Sim.canBuild(built, cell, "Cuboid"))
  }
