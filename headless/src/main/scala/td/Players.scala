package td

/** Anything that turns the board into player intent. [[decide]] is called between
  * steps; the commands it returns are applied by the next [[Sim.step]].
  */
trait Player:
  def decide(w: World): Vector[Command]

object Players:

  /** The autoplay variants by name. `knowledge` only matters for the explorers,
    * which play greedily from it (no exploration). `explorer` is the one that keeps
    * its knowledge between sessions; `explorer-fresh` starts from nothing each time.
    */
  def create(name: String, knowledge: Knowledge, seed: Long): Either[String, Player] =
    name match
      case "naive"    => Right(NaivePlayer)
      case "spender" | "entrance" => Right(new SpenderPlayer(new scala.util.Random(seed)))
      case "explorer" | "explorer-fresh" => Right(new Explorer(knowledge, new scala.util.Random(seed), 0.0))
      case other => Left(s"unknown bot '$other' (want naive, spender, explorer or explorer-fresh)")

  /** Bots that learn: they train before they are measured. */
  def learns(name: String): Boolean = name == "explorer" || name == "explorer-fresh"

  def offerCommand(offer: DraftOffer): Command = offer match
    case DraftOffer.Relic(id)   => Command.PickRelic(id)
    case DraftOffer.Augment(id) => Command.PickAugment(id)

  def socketAugment(w: World): Vector[Command] =
    w.towers.find(t => Sim.canAttachAugment(w, t.cell)).map(t => Command.AttachAugment(t.cell)).toVector

/** The original autoplay in [[Bot]]: builds the cheapest tower on the first free cell. */
object NaivePlayer extends Player:
  def decide(w: World): Vector[Command] = Bot.step(w)

/** Scripted "spender": spends all its gold on the cheapest tower it may use. Each
  * tower goes on a free cell that covers close to the most road cells: it picks at
  * random among the cells within `slack` of the best coverage, so different seeds
  * play different games. Between levels it takes the first offer.
  */
final class SpenderPlayer(rng: scala.util.Random, slack: Double = SpenderPlayer.Slack) extends Player:

  def decide(w: World): Vector[Command] =
    w.draft match
      case Some(d) => d.offers.headOption.map(Players.offerCommand).toVector
      case None =>
        // A pending augment needs a tower to go on; until one exists, keep building.
        val socket = if w.pendingAugment.isDefined then Players.socketAugment(w) else Vector.empty
        if socket.nonEmpty then socket else spendAll(w)

  private def spendAll(w: World): Vector[Command] =
    val basic  = Config.LoadoutPool(w.run.loadout).minBy(_.cost)
    val budget = w.gold / basic.cost
    if budget == 0 then Vector.empty
    else
      val road   = Grid.pathCells(w.level).toVector
      val reach2 = basic.range * basic.range
      def coverage(c: GridPos): Int =
        road.count { r =>
          val dx = (r.x - c.x).toFloat
          val dy = (r.y - c.y).toFloat
          dx * dx + dy * dy <= reach2
        }
      val free = (for y <- 0 until Grid.Size; x <- 0 until Grid.Size yield GridPos(x, y))
        .filter(Sim.canBuild(w, _, basic.name))
        .map(c => c -> coverage(c))
        .toVector
      // Cells are drawn one at a time so a draw is never reused.
      (0 until budget).foldLeft((free, Vector.empty[Command])) { case ((left, cmds), _) =>
        if left.isEmpty then (left, cmds)
        else
          val best = left.map(_._2).max
          val good = left.filter(_._2 >= best * (1 - slack))
          val pick = good(rng.nextInt(good.size))._1
          (left.filterNot(_._1 == pick), cmds :+ Command.Build(pick, basic.name))
      }._2

object SpenderPlayer:
  /** How far below the best road coverage a cell may be and still be picked. */
  val Slack: Double = 0.25
