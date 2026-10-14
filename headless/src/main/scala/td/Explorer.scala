package td

import scala.collection.mutable
import scala.util.Random

/** What the explorer has learned, kept between games. Plain data: learning returns
  * a new value, and that value is what gets saved.
  *
  * - towerWeights / cellWeights: decayed counts of builds that were accepted in
  *   elite games (the best games of a batch).
  * - rejected: cells the game refused a build on (the path, for this level).
  * - waitRate: how often elite games chose to do nothing when they could build.
  * - route: fingerprint of the board it was learned on; knowledge for another
  *   route (a regenerated or resized map) is discarded on load.
  */
final case class Knowledge(
    towerWeights: Map[String, Double] = Map.empty,
    cellWeights: Map[GridPos, Double] = Map.empty,
    rejected: Set[GridPos] = Set.empty,
    waitRate: Double = 0.5,
    batches: Int = 0,
    route: String = ""
)

object Knowledge:
  val Decay: Double      = 0.8
  val Prior: Double      = 0.5
  val EliteShare: Double = 0.2
  val WaitBlend: Double  = 0.5

  /** Folds one batch of scored games into the knowledge. The best [[EliteShare]]
    * of the batch are reinforced; every cell any game was refused on is remembered.
    */
  def learn(k: Knowledge, scored: Vector[(Double, Trace)]): Knowledge =
    val eliteCount = math.max(1, math.ceil(scored.size * EliteShare).toInt)
    val elite      = scored.sortBy { case (s, _) => -s }.take(eliteCount).map(_._2)
    val accepted   = elite.flatMap(_.accepted)
    val decided    = elite.flatMap(_.decisions)
    val waits      = decided.count(_.build.isEmpty)
    val observed   = (waits + 1.0) / (decided.size + 2.0)
    k.copy(
      towerWeights = bump(k.towerWeights, accepted.map(_._1)),
      cellWeights  = bump(k.cellWeights, accepted.map(_._2)),
      rejected     = k.rejected ++ scored.flatMap(_._2.refused),
      waitRate     = WaitBlend * k.waitRate + (1 - WaitBlend) * observed,
      batches      = k.batches + 1
    )

  private def bump[A](weights: Map[A, Double], hits: Vector[A]): Map[A, Double] =
    val decayed = weights.map { case (key, w) => key -> w * Decay }
    hits.foldLeft(decayed)((acc, key) => acc.updated(key, acc.getOrElse(key, 0.0) + 1.0))

/** A moment where the explorer had a real choice. `build` is None when it waited. */
final case class Decision(build: Option[(String, GridPos)])

/** One game's record: the decisions taken, and the cells the game refused. */
final case class Trace(decisions: Vector[Decision], refused: Vector[GridPos]):
  def accepted: Vector[(String, GridPos)] =
    decisions.flatMap(_.build).filterNot { case (_, cell) => refused.contains(cell) }

/** The blind learner. It knows only the controls and what the screen shows: the
  * tower panel (names and costs), the gold count, its own towers, the grid size,
  * and whether a click was refused. It never reads the route, the path cells, or
  * Sim.canBuild; a click on the path simply fails, and it remembers that.
  *
  * With probability `epsilon` it acts at random. Otherwise it samples from what
  * `knowledge` says elite games did.
  */
final class Explorer(knowledge: Knowledge, rng: Random, epsilon: Double) extends Player:
  private val decisions = mutable.ArrayBuffer.empty[Decision]
  private val refusals  = mutable.ArrayBuffer.empty[GridPos]
  private val knownBad  = mutable.Set.from(knowledge.rejected)
  private var pending: Option[GridPos] = None

  def trace: Trace = Trace(decisions.toVector, refusals.toVector)

  def decide(w: World): Vector[Command] =
    pending.foreach { cell =>
      if !w.towers.exists(_.cell == cell) then
        refusals += cell
        knownBad += cell
    }
    pending = None
    w.draft match
      case Some(d) =>
        if d.offers.isEmpty then Vector.empty
        else Vector(Players.offerCommand(d.offers(rng.nextInt(d.offers.size))))
      case None =>
        // A pending augment needs a tower to go on; until one exists, keep building.
        val socket = if w.pendingAugment.isDefined then Players.socketAugment(w) else Vector.empty
        if socket.nonEmpty then socket else chooseBuild(w)

  private def chooseBuild(w: World): Vector[Command] =
    val affordable = Config.LoadoutPool(w.run.loadout).filter(_.cost <= w.gold)
    val occupied   = w.towers.map(_.cell).toSet
    val options    = boardCells.filterNot(c => occupied.contains(c) || knownBad.contains(c))
    if affordable.isEmpty || options.isEmpty then Vector.empty
    else
      val explore = rng.nextDouble() < epsilon
      val wait    = if explore then rng.nextBoolean() else rng.nextDouble() < knowledge.waitRate
      if wait then
        decisions += Decision(None)
        Vector.empty
      else
        val spec =
          if explore then pickUniform(affordable)
          else pickWeighted(affordable)(s => knowledge.towerWeights.getOrElse(s.name, 0.0) + Knowledge.Prior)
        val cell =
          if explore then pickUniform(options)
          else pickWeighted(options)(c => knowledge.cellWeights.getOrElse(c, 0.0) + Knowledge.Prior)
        decisions += Decision(Some(spec.name -> cell))
        pending = Some(cell)
        Vector(Command.Build(cell, spec.name))

  private def boardCells: Vector[GridPos] =
    (for y <- 0 until Grid.Size; x <- 0 until Grid.Size yield GridPos(x, y)).toVector

  private def pickUniform[A](xs: Vector[A]): A =
    xs(rng.nextInt(xs.size))

  private def pickWeighted[A](xs: Vector[A])(weight: A => Double): A =
    val cumulative = xs.iterator.map(weight).scanLeft(0.0)(_ + _).drop(1).toVector
    val target     = rng.nextDouble() * cumulative.last
    val idx        = cumulative.indexWhere(_ > target)
    xs(if idx < 0 then xs.size - 1 else idx)
