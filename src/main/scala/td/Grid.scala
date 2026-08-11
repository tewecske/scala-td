package td

case class GridPos(x: Int, y: Int):
  def centerX: Float = (x + 0.5f) * Config.CellPx
  def centerY: Float = (y + 0.5f) * Config.CellPx

/** The 20x20 board and the fixed enemy route across it.
  *
  * The route is authored as a handful of corner waypoints; both the set of path
  * cells (used for build rules and drawing) and the polyline enemies walk along
  * are derived from it, so there is a single source of truth for "where the
  * path is".
  */
object Grid:
  val Size = Config.GridSize

  /** Corners of the serpentine, entering on the left and exiting on the right. */
  val waypoints: Vector[GridPos] = Vector(
    GridPos(0, 17),
    GridPos(17, 17),
    GridPos(17, 13),
    GridPos(2, 13),
    GridPos(2, 9),
    GridPos(17, 9),
    GridPos(17, 5),
    GridPos(2, 5),
    GridPos(2, 1),
    GridPos(19, 1)
  )

  def inBounds(p: GridPos): Boolean =
    p.x >= 0 && p.x < Size && p.y >= 0 && p.y < Size

  /** Every cell the route passes through, expanded from the waypoint corners. */
  val pathCells: Set[GridPos] =
    waypoints.indices
      .dropRight(1)
      .flatMap(i => cellsBetween(waypoints(i), waypoints(i + 1)))
      .toSet

  private def cellsBetween(a: GridPos, b: GridPos): Vector[GridPos] =
    if a.x == b.x then
      val (lo, hi) = (math.min(a.y, b.y), math.max(a.y, b.y))
      (lo to hi).toVector.map(GridPos(a.x, _))
    else
      val (lo, hi) = (math.min(a.x, b.x), math.max(a.x, b.x))
      (lo to hi).toVector.map(GridPos(_, a.y))

  def isBuildable(p: GridPos): Boolean = inBounds(p) && !pathCells.contains(p)

  // -- the walkable polyline, in world units ---------------------------------

  private val points: Vector[(Float, Float)] =
    waypoints.map(p => (p.centerX, p.centerY))

  /** Distance along the route at which each waypoint sits. */
  private val cumulative: Vector[Float] =
    points.indices
      .dropRight(1)
      .map { i =>
        val (ax, ay) = points(i)
        val (bx, by) = points(i + 1)
        math.hypot(bx - ax, by - ay).toFloat
      }
      .toVector
      .scanLeft(0f)(_ + _)

  /** Total route length in world units. */
  val length: Float = cumulative.last

  /** World position of a walker that has travelled `progress` units along the
    * route. Clamped at both ends.
    */
  def positionAt(progress: Float): (Float, Float) =
    if progress <= 0f then points.head
    else if progress >= length then points.last
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
