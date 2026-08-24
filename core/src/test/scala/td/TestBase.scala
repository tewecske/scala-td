package td

import munit.{BeforeEach, FunSuite}

/** Shared base for test suites. The simulation resolves board/levels/palette off
  * the global `Config` singleton (keyed by the active biome), which is process
  * global. Reset that transient state before every test so one suite's run can't
  * leak its biome into another suite's grid/config reads.
  */
trait TestBase extends FunSuite:
  override def beforeEach(context: BeforeEach): Unit =
    Config.clearActiveBiome()
    Grid.reset()
    super.beforeEach(context)
