package td

case class GridPos(x: Int, y: Int):
  def centerX: Float = (x + 0.5f) * Config.CellPx
  def centerY: Float = (y + 0.5f) * Config.CellPx

/** The board and the per-level enemy routes across it.
  *
  * Each route is authored as a handful of corner waypoints (one per
  * [[LevelConfig]]); both the set of path cells (used for build rules and
  * drawing) and the polyline enemies walk along are derived from it, so there
  * is a single source of truth for "where the path is". Derived data is cached
  * per (config, level) and rebuilt whenever either changes, so a reload or a
  * level transition re-derives the grid from the right route.
  */
object Grid:

  private case class Derived(
      size: Int,
      pathCells: Set[GridPos],
      points: Vector[(Float, Float)],
      cumulative: Vector[Float],
      length: Float
  )

  @volatile private var cachedSizeFor: GameConfig | Null = null
  @volatile private var cachedSize: Int                  = 0

  @volatile private var cachedRouteFor: (GameConfig, Int) | Null = null
  @volatile private var cachedRoute: Derived | Null              = null

  private def size: Int =
    val cfg = Config.game
    if (cachedSizeFor eq cfg) then cachedSize
    else
      val s = cfg.board.gridSize
      cachedSizeFor = cfg
      cachedSize = s
      s

  private def routeDerived(level: Int): Derived =
    val cfg = Config.game
    val key = (cfg, level)
    if (cachedRouteFor eq key) && cachedRoute != null then cachedRoute.nn
    else
      val d = build(cfg, level)
      cachedRouteFor = key
      cachedRoute = d
      d

  private def build(cfg: GameConfig, level: Int): Derived =
    val wp = Config.levelAt(level).route.waypoints
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

  def Size: Int = size

  def inBounds(p: GridPos): Boolean =
    p.x >= 0 && p.x < Size && p.y >= 0 && p.y < Size

  /** Every cell the current level's route passes through, expanded from the
    * waypoint corners.
    */
  def pathCells(level: Int): Set[GridPos] = routeDerived(level).pathCells

  private def cellsBetween(a: GridPos, b: GridPos): Vector[GridPos] =
    if a.x == b.x then
      val (lo, hi) = (math.min(a.y, b.y), math.max(a.y, b.y))
      (lo to hi).toVector.map(GridPos(a.x, _))
    else
      val (lo, hi) = (math.min(a.x, b.x), math.max(a.x, b.x))
      (lo to hi).toVector.map(GridPos(_, a.y))

  def isBuildable(p: GridPos, level: Int): Boolean =
    inBounds(p) && !pathCells(level).contains(p)

  // -- the walkable polyline, in world units ---------------------------------

  /** Total route length of the current level, in world units. */
  def length(level: Int): Float = routeDerived(level).length

  /** World position of a walker that has travelled `progress` units along the
    * level's route. Clamped at both ends.
    */
  def positionAt(progress: Float, level: Int): (Float, Float) =
    val d = routeDerived(level)
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
