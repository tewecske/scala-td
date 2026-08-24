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

  private case class BranchLane(
      side: RouteSide,
      points: Vector[(Float, Float)],
      cumulative: Vector[Float],
      length: Float,
      forkProgress: Float,
      mergeProgress: Float
  )

  private case class Derived(
      size: Int,
      pathCells: Set[GridPos],
      points: Vector[(Float, Float)],
      cumulative: Vector[Float],
      length: Float,
      branches: Vector[BranchLane]
  )

  @volatile private var cachedSizeFor: (GameConfig, Option[String]) | Null = null
  @volatile private var cachedSize: Int                                       = 0

  @volatile private var cachedRouteFor: (GameConfig, Option[String], Int) | Null = null
  @volatile private var cachedRoute: Derived | Null                              = null

  /** Drops all cached derived routes/sizes. Used by tests between cases so a
    * stale active-biome key can never leak a prior run's map into the next.
    */
  def reset(): Unit =
    cachedSizeFor = null
    cachedSize    = 0
    cachedRouteFor = null
    cachedRoute    = null

  private def size: Int =
    val cfg = Config.game
    val key = (cfg, Config.activeBiomeId)
    if (cachedSizeFor != null) && (cachedSizeFor.nn == key) then cachedSize
    else
      val s = Config.GridSize
      cachedSizeFor = key
      cachedSize = s
      s

  private def routeDerived(level: Int): Derived =
    val cfg = Config.game
    val key = (cfg, Config.activeBiomeId, level)
    if (cachedRouteFor != null) && (cachedRouteFor.nn == key) && cachedRoute != null then cachedRoute.nn
    else
      val d = build(cfg, level)
      cachedRouteFor = key
      cachedRoute = d
      d

  private def build(cfg: GameConfig, level: Int): Derived =
    val route = Config.levelAt(level).route
    val wp    = route.waypoints

    val trunkCells = wp.indices
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
    val length = cumulative.last

    val branches = route.branches.map { b =>
      val laneWp        = wp(b.from) +: b.waypoints :+ wp(b.to)
      val lanePoints    = laneWp.map(p => (p.centerX, p.centerY))
      val laneCumulative = lanePoints.indices
        .dropRight(1)
        .map { i =>
          val (ax, ay) = lanePoints(i)
          val (bx, by) = lanePoints(i + 1)
          math.hypot(bx - ax, by - ay).toFloat
        }
        .scanLeft(0f)(_ + _)
        .toVector
      BranchLane(b.side, lanePoints, laneCumulative, laneCumulative.last, cumulative(b.from), cumulative(b.to))
    }

    val branchCells = route.branches.flatMap { b =>
      val laneWp = wp(b.from) +: b.waypoints :+ wp(b.to)
      laneWp.indices.dropRight(1).flatMap(i => cellsBetween(laneWp(i), laneWp(i + 1)))
    }.toSet

    Derived(Config.GridSize, trunkCells ++ branchCells, points, cumulative, length, branches)

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

  /** Total route length of the current level, in world units. Branches re-join
    * the trunk, so every lane shares this same canonical total — an enemy leaks
    * exactly when its progress reaches this.
    */
  def length(level: Int): Float = routeDerived(level).length

  /** One fork on a level's route: which branch lane it is, which side that lane
    * sits on, and the canonical progress where it diverges ([[forkProgress]])
    * and re-joins ([[mergeProgress]]). The trunk segment the branch bypasses is
    * the opposite side.
    */
  case class Fork(branchIndex: Int, side: RouteSide, forkProgress: Float, mergeProgress: Float)

  /** The forks of a level's route, in the order an enemy meets them. */
  def forks(level: Int): Vector[Fork] =
    val d = routeDerived(level)
    d.branches.zipWithIndex.map { case (b, i) => Fork(i, b.side, b.forkProgress, b.mergeProgress) }

  /** World position of a walker that has travelled `progress` units along the
    * level's route, on the given lane. `lane = None` is the trunk; a branch
    * index is that branch's lane. Branches map canonical progress linearly, so
    * movement stays continuous and an enemy never teleports at a fork/merge.
    * Clamped at both ends.
    */
  def positionAt(progress: Float, level: Int, lane: Option[Int] = None): (Float, Float) =
    val d = routeDerived(level)
    lane.flatMap(i => d.branches.lift(i)) match
      case Some(b) if progress >= b.forkProgress && progress <= b.mergeProgress =>
        val span = b.mergeProgress - b.forkProgress
        val dist = if span <= 0f then 0f else (progress - b.forkProgress) / span * b.length
        pointAlong(b.points, b.cumulative, dist)
      case _ =>
        pointAlong(d.points, d.cumulative, progress)

  private def pointAlong(points: Vector[(Float, Float)], cumulative: Vector[Float], dist: Float): (Float, Float) =
    if dist <= 0f then points.head
    else if dist >= cumulative.last then points.last
    else
      // Last waypoint at or before `dist`; the segment starts there.
      val i = cumulative.lastIndexWhere(_ <= dist).max(0).min(points.size - 2)
      val (ax, ay) = points(i)
      val (bx, by) = points(i + 1)
      val segLen   = cumulative(i + 1) - cumulative(i)
      val t        = if segLen <= 0f then 0f else (dist - cumulative(i)) / segLen
      (ax + (bx - ax) * t, ay + (by - ay) * t)

  /** Grid cell containing a world point, whether or not it is on the board. */
  def cellAt(wx: Float, wy: Float): GridPos =
    GridPos(math.floor(wx / Config.CellPx).toInt, math.floor(wy / Config.CellPx).toInt)
