package td

case class GridPos(x: Int, y: Int):
  def centerX: Float = (x + 0.5f) * Config.CellPx
  def centerY: Float = (y + 0.5f) * Config.CellPx

/** The board and the fixed enemy route across it.
  *
  * The route is authored as a handful of corner waypoints ([[GameConfig.route]]);
  * both the set of path cells (used for build rules and drawing) and the
  * polyline enemies walk along are derived from it, so there is a single source
  * of truth for "where the path is". Derived data is cached and rebuilt whenever
  * the active config changes, so a reload re-derives the grid from the new route.
  */
object Grid:

  private case class Derived(
      size: Int,
      pathCells: Set[GridPos],
      points: Vector[(Float, Float)],
      cumulative: Vector[Float],
      length: Float
  )

  @volatile private var cachedFor: GameConfig | Null = null
  @volatile private var cached: Derived | Null       = null

  private def derived: Derived =
    val cfg = Config.game
    if (cachedFor eq cfg) && cached != null then cached.nn
    else
      val d = build(cfg)
      cachedFor = cfg
      cached = d
      d

  private def build(cfg: GameConfig): Derived =
    val wp = cfg.route.waypoints
    val pathCells = wp.indices
      .dropRight(1)
      .flatMap(i => cellsBetween(wp(i), wp(i + 1)))
      .toSet
    val points = wp.map(p => (p.centerX, p.centerY))
    val cumulative = points.indices
      .dropRight(1)
      .map { i =>
        val (ax, ay) = points(i)
        val (bx, by) = points(i + 1)
        math.hypot(bx - ax, by - ay).toFloat
      }
      .scanLeft(0f)(_ + _)
      .toVector
    Derived(cfg.board.gridSize, pathCells, points, cumulative, cumulative.last)

  def Size: Int = derived.size

  def inBounds(p: GridPos): Boolean =
    p.x >= 0 && p.x < Size && p.y >= 0 && p.y < Size

  /** Every cell the route passes through, expanded from the waypoint corners. */
  def pathCells: Set[GridPos] = derived.pathCells

  private def cellsBetween(a: GridPos, b: GridPos): Vector[GridPos] =
    if a.x == b.x then
      val (lo, hi) = (math.min(a.y, b.y), math.max(a.y, b.y))
      (lo to hi).toVector.map(GridPos(a.x, _))
    else
      val (lo, hi) = (math.min(a.x, b.x), math.max(a.x, b.x))
      (lo to hi).toVector.map(GridPos(_, a.y))

  def isBuildable(p: GridPos): Boolean = inBounds(p) && !pathCells.contains(p)

  // -- the walkable polyline, in world units ---------------------------------

  /** Total route length in world units. */
  def length: Float = derived.length

  /** World position of a walker that has travelled `progress` units along the
    * route. Clamped at both ends.
    */
  def positionAt(progress: Float): (Float, Float) =
    val d = derived
    val points = d.points
    val cumulative = d.cumulative
    if progress <= 0f then points.head
    else if progress >= d.length then points.last
    else
      // Last waypoint at or before `progress`; the segment starts there.
      val i = cumulative.lastIndexWhere(_ <= progress).max(0).min(points.size - 2)
      val (ax, ay) = points(i)
      val (bx, by) = points(i + 1)
      val segLen   = cumulative(i + 1) - cumulative(i)
      val t        = if segLen <= 0f then 0f else (progress - cumulative(i)) / segLen
      (ax + (bx - ax) * t, ay + (by - ay) * t)

  /** Grid cell containing a world point, whether or not it is on the board. */
  def cellAt(wx: Float, wy: Float): GridPos =
    GridPos(math.floor(wx / Config.CellPx).toInt, math.floor(wy / Config.CellPx).toInt)
