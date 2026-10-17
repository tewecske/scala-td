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
      case "builder"  => Right(new BuilderPlayer(new scala.util.Random(seed)))
      case "explorer" | "explorer-fresh" => Right(new Explorer(knowledge, new scala.util.Random(seed), 0.0))
      case other => Left(s"unknown bot '$other' (want naive, spender, builder, explorer or explorer-fresh)")

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

/** Scripted "builder": uses every tower its loadout allows. Whenever it has gold
  * it rates each tower on its best free cell as expected damage per second times
  * the road cells in reach, per gold. The damage estimate counts the level's enemy
  * resistances to the tower's damage type (biome included), blast radius, burn,
  * slow (worth less with every slowing tower already built) and the run's talents.
  * It builds the best one; when that costs more than it has and it already owns two
  * towers, it saves up instead of settling for a worse one. Between levels it takes
  * the first offer.
  */
final class BuilderPlayer(rng: scala.util.Random, slack: Double = SpenderPlayer.Slack) extends Player:
  import BuilderPlayer.*

  def decide(w: World): Vector[Command] =
    w.draft match
      case Some(d) => d.offers.headOption.map(Players.offerCommand).toVector
      case None =>
        val socket = if w.pendingAugment.isDefined then Players.socketAugment(w) else Vector.empty
        if socket.nonEmpty then socket else build(w)

  private def build(w: World): Vector[Command] =
    val pool   = Config.LoadoutPool(w.run.loadout)
    val eff    = Meta.effects(w.run.talents)
    val resist = levelResist(w)
    val road   = Grid.pathCells(w.level).toVector
    val cells  = (for y <- 0 until Grid.Size; x <- 0 until Grid.Size yield GridPos(x, y)).toVector

    def coverage(c: GridPos, t: TowerSpec): Int =
      val reach = t.range * eff.rangeMult
      road.count { r =>
        val dx = (r.x - c.x).toFloat
        val dy = (r.y - c.y).toFloat
        dx * dx + dy * dy <= reach * reach
      }

    // one tower per pass: gold, free cells and the slow count change after each
    @annotation.tailrec
    def loop(gold: Int, taken: Set[GridPos], slowing: Int, out: Vector[Command]): Vector[Command] =
      val options = pool.flatMap { t =>
        val free = cells.filter(c => !taken(c) && Sim.canBuild(w, c, t.name)).map(c => c -> coverage(c, t))
        Option.when(free.nonEmpty) {
          val best = free.map(_._2).max
          val good = free.filter(_._2 >= best * (1 - slack))
          val cell = good(rng.nextInt(good.size))._1
          (t, cell, dps(t, resist, eff, slowing) * best / t.cost)
        }
      }
      if options.isEmpty then out
      else
        val top        = options.maxBy(_._3)
        val affordable = options.filter(_._1.cost <= gold)
        val owned      = w.towers.size + out.size
        val pick =
          if top._1.cost <= gold then Some(top)
          else if owned >= SaveAfter then None // save up for the best one
          else affordable.maxByOption(_._3)
        pick match
          case None => out
          case Some((t, cell, _)) =>
            loop(gold - t.cost, taken + cell, slowing + (if t.slows then 1 else 0), out :+ Command.Build(cell, t.name))

    loop(w.gold, Set.empty, w.towers.count(_.spec.slows), Vector.empty)

object BuilderPlayer:
  /** Towers it must own before it starts saving up for a better one. */
  val SaveAfter = 2
  /** Damage per second a full-time slow is worth, before diminishing returns. */
  val SlowWorth = 20f

  /** Health-weighted mean resistance of this level's enemies to each damage kind,
    * with the biome's resist theme layered on.
    */
  def levelResist(w: World): Map[DamageKind, Float] =
    val groups = Config.withContext(w.biome, w.level)(Config.levelAt(w.level)).waves.flatMap(_.composition)
    val specs  = groups.map((n, c) => (Config.enemySpec(n), c))
    val weight = specs.map((s, c) => s.baseHp * c).sum
    DamageKind.values.map { k =>
      k -> (if weight <= 0f then 0f
            else specs.map((s, c) => s.resist.layered(w.resistTheme)(k) * s.baseHp * c).sum / weight)
    }.toMap

  /** Expected damage per second of one tower against this level's enemies. */
  def dps(t: TowerSpec, resist: Map[DamageKind, Float], eff: TalentEffects, slowing: Int): Float =
    val hit     = t.damage * (1f - resist(t.kind)) * eff.damageMult * eff.kindMult(t.kind)
    val targets = 1f + math.min(3f, t.splashRadius * 1.5f)
    val aim     = if t.groundTarget then 0.85f else 1f
    val direct  = hit * targets * aim * eff.fireRateMult / t.fireInterval
    val burn    = if t.burns then t.burnDps * (1f - resist(DamageKind.Fire)) * math.min(1f, t.burnDuration / t.fireInterval) else 0f
    val slow    =
      if t.slows then (1f - t.slowFactor) * math.min(1f, t.slowDuration / t.fireInterval) * SlowWorth / (1 + slowing)
      else 0f
    direct + burn + slow
