package td

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Balances the campaign one level at a time against the spender bot, with the
  * choices a player really has when they reach that level.
  *
  *  - Level 1 is played as a fresh run: default loadout, no talents, no draft.
  *    It should be won most of the time while costing a few lives.
  *  - Later levels are played with every option that exists by then, one at a
  *    time: no unlock, each tower the loadout could bring instead of the default,
  *    and each talent (with its prerequisites) that one run's meta currency can
  *    buy; each of those crossed with every pick the previous draft could offer.
  *    They start with the lives a plain run typically has left (lives carry over).
  *    Such a level should be lost with no unlock and an average pick, and won
  *    with an unlock or a good pick.
  *
  * Only the waves change (see [[Balance.harden]] and [[Balance.ease]]).
  */
object Stages:

  /** One way into a level: which unlock was used (None = no unlock) and the setup. */
  final case class Entry(unlock: Option[String], setup: Setup)

  final case class Result(option: Entry, stats: Stats)

  final case class Settings(
      rounds: Int,
      games: Int,
      comboGames: Int,
      firstLow: Double,
      livesLow: Double,
      livesHigh: Double,
      goodShare: Double,
      winBar: Double,
      steps: Balance.Steps,
      seed: Long,
      think: Int,
      biome: String
  )

  // -- what a player can have at a level ------------------------------------------

  /** Meta currency from one run that cleared the first `cleared` levels: the per-level
    * payout plus the per-wave payout for every wave in them (no win bonus).
    */
  def budget(cleared: Int, biome: String): Int =
    Config.withBiome(biome) {
      (0 until cleared).map(l => Config.PayoutPerLevel + Config.PayoutPerWave * Config.levelAt(l).waves.size).sum
    }

  /** Each talent with the prerequisites it needs, priced, cheapest first. */
  def talentChains: Vector[(Vector[String], Int)] =
    def chain(id: String): Vector[String] =
      val t = Config.talents.find(_.id == id).get
      (t.prereqs.flatMap(chain) :+ id).distinct
    Config.talents.map { t =>
      val ids = chain(t.id)
      ids -> ids.map(i => Config.talents.find(_.id == i).get.cost).sum
    }.sortBy { case (ids, cost) => (cost, ids.last) }

  /** The loadout a fresh run starts with. */
  def defaultLoadout: Vector[String] = Config.Loadout.defaultSelected

  /** Every option for `level` (0-based): no unlock, a different tower, or one talent
    * chain the budget covers; each crossed with every eligible draft pick. Level 0
    * has no draft and no budget, so it has the single no-unlock option.
    */
  def options(level: Int, biome: String, livesLost: Int = 0): Vector[Entry] =
    val base = Setup(loadout = defaultLoadout, livesLost = livesLost)
    if level == 0 then Vector(Entry(None, base))
    else
      val money   = budget(level, biome)
      val towers  = Meta.unlockedTowers(MetaData.empty).toVector.sorted.filterNot(defaultLoadout.contains)
      val bought  = Config.Loadout.unlockCosts.toVector.sorted.collect { case (t, c) if c <= money => t }
      val unlocks: Vector[(Option[String], Setup)] =
        (None -> base) +:
          ((towers ++ bought).map(t => Some(s"tower:$t") -> base.copy(loadout = Vector(t))) ++
            talentChains.collect { case (ids, cost) if cost <= money =>
              Some(s"talent:${ids.last}") -> base.copy(talents = ids)
            })
      for
        (unlock, setup) <- unlocks
        pick            <- DraftGen.eligibleItems(setup.talents)
      yield pick match
        case DraftOffer.Relic(id)   => Entry(unlock, setup.copy(relics = Vector(id)))
        case DraftOffer.Augment(id) => Entry(unlock, setup.copy(augment = Some(id)))

  // -- measuring -------------------------------------------------------------------

  def play(o: Entry, level: Int, games: Int, s: Settings, sink: Training.Sink): Result =
    val played = (0 until games).toVector.map { i =>
      val seed = s.seed + i
      val p    = new SpenderPlayer(new scala.util.Random(seed))
      Scenario.play(p, s.biome, level, seed, s.think, setup = o.setup)
    }
    played.foreach(g => sink("stage", level, g))
    Result(o, Stats.of(played.map(_.outcome)))

  /** Lives a plain run has lost by the time it reaches `level`: for each earlier
    * level, the mean lives lost in the games its no-unlock options won, rounded.
    */
  def carriedLoss(level: Int, s: Settings, sink: Training.Sink): Int =
    (0 until level).foldLeft(0) { (lost, l) =>
      val plain = options(l, s.biome, lost).filter(_.unlock.isEmpty)
      val won   = plain.map(o => play(o, l, if l == 0 then s.games else s.comboGames, s, sink).stats).filter(_.wins > 0)
      if won.isEmpty then lost
      else lost + math.round(won.map(_.livesLostWon).sum / won.size).toInt
    }

  def measure(cfg: GameConfig, level: Int, s: Settings, sink: Training.Sink): Vector[Result] =
    Config.use(cfg)
    val carried = carriedLoss(level, s, sink)
    val opts    = options(level, s.biome, carried)
    opts.map(o => play(o, level, if level == 0 then s.games else s.comboGames, s, sink))

  /** Level 1: the plain run should win at least `firstLow` of its games and lose
    * between `livesLow` and `livesHigh` lives on average in the games it wins.
    * Later levels: too easy when more than `goodShare` of the draft picks win (at
    * least `winBar` of their games) with no unlock; too hard when no option at all wins.
    */
  def verdict(level: Int, results: Vector[Result], s: Settings): Balance.Verdict =
    if level == 0 then
      val st = results.head.stats
      if st.winRate < s.firstLow || st.livesLostWon > s.livesHigh then Balance.Verdict.TooHard
      else if st.livesLostWon < s.livesLow then Balance.Verdict.TooEasy
      else Balance.Verdict.Ok
    else
      val plain   = results.filter(_.option.unlock.isEmpty)
      val winning = (rs: Vector[Result]) => rs.filter(_.stats.winRate >= s.winBar)
      if winning(plain).size > s.goodShare * plain.size then Balance.Verdict.TooEasy
      else if winning(results).isEmpty then Balance.Verdict.TooHard
      else Balance.Verdict.Ok

  // -- reporting -------------------------------------------------------------------

  def pickLabel(o: Entry): String =
    o.setup.relics.headOption.map("relic:" + _).orElse(o.setup.augment.map("augment:" + _)).getOrElse("-")

  /** One line per unlock: how many picks win with it, and its best and mean win rate. */
  def summary(level: Int, results: Vector[Result], s: Settings): Vector[String] =
    if level == 0 then Vector(s"  plain run  ${results.head.stats.line}")
    else
      results.groupBy(_.option.unlock).toVector.sortBy(_._1.getOrElse("")).map { case (unlock, rs) =>
        val rates = rs.map(_.stats.winRate)
        val wins  = rs.count(_.stats.winRate >= s.winBar)
        val best  = rs.maxBy(r => (r.stats.winRate, -r.stats.livesLostWon))
        f"  ${unlock.getOrElse("no unlock")}%-26s picks winning ${wins}%2d/${rs.size}%-2d  " +
          f"mean ${100 * rates.sum / rates.size}%3.0f%%  best ${100 * best.stats.winRate}%3.0f%% " +
          f"losing ${best.stats.livesLostWon}%.1f lives (${pickLabel(best.option)})"
      }

  def writeCsv(path: String, level: Int, results: Vector[Result]): Unit =
    val header = "level,unlock,loadout,talents,pick,lives_down_at_start,games,wins,win_rate,lives_lost_when_won"
    val rows = results.map { r =>
      val o = r.option
      Vector(
        (level + 1).toString, o.unlock.getOrElse("none"), o.setup.loadout.mkString("+"),
        o.setup.talents.mkString("+"), pickLabel(o), o.setup.livesLost.toString, r.stats.games.toString, r.stats.wins.toString,
        "%.2f".format(r.stats.winRate), "%.1f".format(r.stats.livesLostWon)
      ).mkString(",")
    }
    val p = Paths.get(path)
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.writeString(p, (header +: rows).mkString("", "\n", "\n"), StandardCharsets.UTF_8)

  // -- balancing -------------------------------------------------------------------

  /** Balances `level` and returns the config to keep and the results it was measured with. */
  def run(start: GameConfig, level: Int, s: Settings, sink: Training.Sink, log: String => Unit): (GameConfig, Vector[Result]) =
    var last = Map.empty[GameConfig, Vector[Result]]
    val label = s"level ${level + 1}"
    val kept = Balance.search(start, s.rounds, label,
      cfg =>
        val results = measure(cfg, level, s, sink)
        last += cfg -> results
        val counts = cfg.levels(level).waves.map(_.count).mkString("/")
        val lives  = results.head.option.setup.livesLost
        log(s"$label  waves $counts  ${results.size} option(s), starting $lives li${if lives == 1 then "fe" else "ves"} down")
        summary(level, results, s).foreach(log)
        verdict(level, results, s),
      (cfg, harder) =>
        if harder then Balance.harden(cfg, s.steps, s.biome, level) else Balance.ease(cfg, s.steps, s.biome, level),
      log)
    val results = last.getOrElse(kept, measure(kept, level, s, sink))
    (kept, results)
