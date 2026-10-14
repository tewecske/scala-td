package td

import munit.{BeforeEach, FunSuite}

/** Shared base for test suites. The simulation resolves board/levels/palette off
  * the global `Config` singleton (keyed by the active biome), which is process
  * global. Reset that transient state before every test so one suite's run can't
  * leak its biome into another suite's grid/config reads.
  */
trait TestBase extends FunSuite:

  /** A buildable cell about two cells from `at`, so a tower placed there fires at
    * an enemy standing on `at` and the shot is still in flight for a step (a tower on
    * top of its target would resolve the hit at once). Uses the level-one grid.
    */
  protected def cellNear(at: GridPos): GridPos =
    val size = Config.GridSize
    (for x <- 0 until size; y <- 0 until size yield GridPos(x, y))
      .find { c =>
        val d = math.hypot(c.x - at.x, c.y - at.y)
        d >= 1.5 && d <= 2.5 && Grid.isBuildable(c, 0)
      }
      .getOrElse(sys.error(s"no buildable cell near $at"))

  override def beforeEach(context: BeforeEach): Unit =
    Config.clearActiveBiome()
    Grid.reset()
    super.beforeEach(context)
