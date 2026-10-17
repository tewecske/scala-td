package td

import java.nio.file.{Files, Paths}
import scala.util.Random

/** Command-line experiments on the level list. `bench` plays a bot for stats (and
  * can save its best and worst games as replays), `train` trains the explorer per
  * level, `balance` retunes levels until the explorer stops beating them too easily,
  * `gen-levels` adds random levels to the config, and `replay` checks a saved game.
  * None of them write journal or meta save files.
  *
  * `--level` takes a 1-based level number or `all`.
  */
/** A command was rejected: bad arguments, a missing file, and so on. Thrown rather
  * than exiting so a long-lived session (batch or repl) can report it and go on.
  */
final case class CommandError(msg: String, usage: String) extends RuntimeException(msg)

/** The command printed its help and has nothing more to do. */
object CommandDone extends RuntimeException

object Experiments:

  private val BenchUsage =
    """usage: headless bench [options]
      |  --bot <name>         naive | spender | explorer | explorer-fresh (default explorer)
      |                       explorer continues from its saved knowledge and saves what it
      |                       learns; explorer-fresh starts from nothing and saves nothing
      |  --learn <n>          training batches a learning bot plays before it is measured
      |                       (default 10)
      |  --learn-size <n>     games per training batch (default 50)
      |  --level <n|all>      level to play, 1-based (default all)
      |  --games <n>          games per level (default 100)
      |  --seed <long>        first seed (default 1)
      |  --think <n>          bot acts every n steps (default 5)
      |  --biome <id>         biome to play in (default front-lawn)
      |  --record <n>         save the n best and n worst games as replays (default 0)
      |  --towers <a,b,..>    towers the bot may use (default all)
      |  --rolled             play the runs the game rolls (random biome, twist and enemy
      |                       speeds) instead of the plain biome
      |  --config <path>      load config from JSON file (default bundled)""".stripMargin

  private val TrainUsage =
    """usage: headless train [options]
      |  --level <n|all>      level to train on, 1-based (default all)
      |  --batches <n>        batches per level (default 20)
      |  --batch-size <n>     games per batch (default 50)
      |  --eval-games <n>     greedy games to test with at the end (default 100)
      |  --seed <long>        first seed (default 1)
      |  --think <n>          bot acts every n steps (default 5)
      |  --biome <id>         biome to play in (default front-lawn)
      |  --fresh              ignore saved knowledge and start from zero
      |  --config <path>      load config from JSON file (default bundled)""".stripMargin

  private val BalanceUsage =
    """usage: headless balance [options]
      |  --pass <name>        global | local | both (default both: global first, then each level)
      |                       global scales every enemy type's health and speed together;
      |                       local changes only the number and types of units per level
      |  --level <n|all>      level(s) to balance, in order (default all)
      |  --rounds <n>         max measure/adjust rounds per pass and level (default 6)
      |  --batches <n>        training batches for each fresh explorer (default 8)
      |  --batch-size <n>     games per batch (default 40)
      |  --eval-games <n>     greedy games per measurement (default 100)
      |  --easy-win <f>       win rate at or above which a level is too easy (default 0.9)
      |  --easy-lives <f>     ...if it also loses at most this many lives per win (default 2)
      |  --hard-win <f>       win rate below which a level is too hard (default 0.5)
      |  --promote <f>        share of a wave's unit moved one variant up or down per step
      |                       (default 0.25)
      |  --resize <f>         share of a wave added or removed per step, at least 1 (default 0.1)
      |  --global-hp <f>      enemy health factor per global step (default 1.1)
      |  --global-speed <f>   enemy speed factor per global step (default 1.03)
      |  --towers <a,b,..>    towers the explorer may use (default all)
      |  --seed <long>        first seed (default 1)
      |  --think <n>          bot acts every n steps (default 5)
      |  --biome <id>         biome to play in (default front-lawn)
      |  --out <path>         where to write the balanced config (default data/balance/config.json)
      |  --config <path>      start from this config JSON (default bundled)""".stripMargin

  private val StageUsage =
    """usage: headless stage [options]
      |  --level <n,..>       levels to balance in order, 1-based (default 1,2)
      |  --rounds <n>         max measure/adjust rounds per level (default 12)
      |  --games <n>          spender games for level 1 (default 100)
      |  --combo-games <n>    spender games per unlock + draft pick on later levels (default 20)
      |  --first-low <f>      level 1 is too hard below this win rate (default 0.6)
      |  --lives-low <f>      level 1 is too easy when won games lose fewer lives (default 1)
      |  --lives-high <f>     level 1 is too hard when won games lose more lives (default 3)
      |  --win-bar <f>        an option "wins" a later level at this win rate (default 0.5)
      |  --good-share <f>     later level is too easy when more than this share of draft picks
      |                       win with no unlock (default 0.3); too hard when nothing wins
      |  --promote <f>        share of a wave's unit moved one variant per step (default 0.25)
      |  --resize <f>         share of a wave added or removed per step (default 0.1)
      |  --seed <long>        first seed (default 1)
      |  --think <n>          bot acts every n steps (default 5)
      |  --biome <id>         biome to play in (default front-lawn)
      |  --out <path>         balanced config (default data/balance/config.json); per-option
      |                       results go next to it as stage-L<n>.csv
      |  --record <n>         replays saved per balanced level (default 2; 0 = none): level 1
      |                       keeps the n best and worst games, later levels the best game
      |                       of each unlock and the worst with no unlock
      |  --config <path>      start from this config JSON (default bundled)""".stripMargin

  private val GenUsage =
    """usage: headless gen-levels [options]
      |  --count <n>          levels to generate (default 5)
      |  --size <n>           board size, 10 or 20 (default: the config's board size)
      |  --shape <name>       snake | loop | fork | mixed (default mixed)
      |  --biome <id>         make a new biome with this id (its own board size); without it
      |                       the levels join the global campaign
      |  --biome-name <text>  display name for --biome (default: the id)
      |  --seed <long>        generator seed (default 42)
      |  --into <path>        config file to change (default core/src/main/resources/config.json)""".stripMargin

  private val ReplayUsage =
    """usage: headless replay <file.json>
      |  replays the saved game against its saved config snapshot and checks the end state""".stripMargin

  def bench(args: List[String]): Unit =
    val o = Opts.parse(args, BenchUsage,
      valued = Set("--bot", "--level", "--games", "--seed", "--think", "--biome", "--record", "--config", "--towers",
        "--learn", "--learn-size"),
      flagged = Set("--rolled"))
    loadConfig(o)
    val bot     = o.str("--bot", "explorer")
    val biome   = o.str("--biome", Scenario.DefaultBiome)
    val seed    = o.long("--seed", 1L)
    val games   = o.int("--games", 100)
    val think   = o.int("--think", 5)
    val record  = o.int("--record", 0)
    val towers  = o.values.get("--towers").map(_.split(",").toVector.map(_.trim)).getOrElse(Vector.empty)
    val levels  = levelsOf(o.str("--level", "all"), BenchUsage, biome)
    Players.create(bot, Knowledge(), seed).left.foreach(err => Opts.fail(err, BenchUsage))
    val hash = ReplayStore.saveConfig(ReplayStore.DefaultDir, Config.game)
    val learnBatches = o.int("--learn", 10)
    val learnSize    = o.int("--learn-size", 50)
    levels.foreach { level =>
      val knowledge =
        if !Players.learns(bot) then Knowledge()
        else
          val start =
            if bot == "explorer" then KnowledgeStore.forLevel(biome, level)
            else Knowledge(route = KnowledgeStore.routeKey(biome, level))
          val learned = Training.train(start, learnBatches, learnSize, seed, think, biome, level, towers,
            progressSink(biome, bot = bot), _ => ())
          if bot == "explorer" then KnowledgeStore.save(KnowledgeStore.pathFor(biome, level), learned)
          learned
      val played = (0 until games).toVector.map { i =>
        val s = seed + i
        val p = Players.create(bot, knowledge, s).getOrElse(Opts.fail(bot, BenchUsage))
        Scenario.play(p, biome, level, s, think, setup = Setup(loadout = towers, rolled = o.flag("--rolled")))
      }
      val enemies = Scenario.enemyCount(biome, level)
      played.foreach(g => recordGame("bench", bot, biome, level, g))
      println(s"L${level + 1} ${levelName(biome, level)}  bot=$bot  batches_learned=${knowledge.batches}")
      println("  " + Stats.of(played.map(_.outcome)).line)
      if record > 0 then saveExtremes(bot, biome, level, think, hash, played, enemies, record)
    }

  def train(args: List[String]): Unit =
    val o = Opts.parse(args, TrainUsage,
      valued = Set("--level", "--batches", "--batch-size", "--eval-games", "--seed", "--think", "--biome", "--config", "--towers"),
      flagged = Set("--fresh"))
    loadConfig(o)
    val biome = o.str("--biome", Scenario.DefaultBiome)
    val seed  = o.long("--seed", 1L)
    val think = o.int("--think", 5)
    val towers = o.values.get("--towers").map(_.split(",").toVector.map(_.trim)).getOrElse(Vector.empty)
    levelsOf(o.str("--level", "all"), TrainUsage, biome).foreach { level =>
      val path  = KnowledgeStore.pathFor(biome, level)
      val start =
        if o.flag("--fresh") then Knowledge(route = KnowledgeStore.routeKey(biome, level))
        else KnowledgeStore.forLevel(biome, level)
      println(s"training L${level + 1} ${levelName(biome, level)} from batch ${start.batches}")
      val learned = Training.train(start, o.int("--batches", 20), o.int("--batch-size", 50), seed, think, biome, level, towers, progressSink(biome), println)
      KnowledgeStore.save(path, learned)
      println(s"saved $path (batches=${learned.batches}, deadCells=${learned.rejected.size})")
      val tested = Training.evaluate(learned, o.int("--eval-games", 100), seed + 1000000L, think, biome, level, towers)
      tested.foreach(g => recordGame("test", "explorer", biome, level, g))
      println(s"greedy test  ${Stats.of(tested.map(_.outcome)).line}")
    }

  def balance(args: List[String]): Unit =
    val o = Opts.parse(args, BalanceUsage,
      valued = Set("--pass", "--level", "--rounds", "--batches", "--batch-size", "--eval-games", "--easy-win",
        "--easy-lives", "--hard-win", "--promote", "--resize", "--global-hp", "--global-speed", "--seed", "--think",
        "--biome", "--out", "--config", "--towers"))
    loadConfig(o)
    val settings = Balance.Settings(
      rounds = o.int("--rounds", 6),
      batches = o.int("--batches", 8),
      batchSize = o.int("--batch-size", 40),
      evalGames = o.int("--eval-games", 100),
      easyWin = o.dbl("--easy-win", 0.9),
      easyLives = o.dbl("--easy-lives", 2.0),
      hardWin = o.dbl("--hard-win", 0.5),
      steps = Balance.Steps(promoteShare = o.dbl("--promote", 0.25), resizeShare = o.dbl("--resize", 0.1)),
      seed = o.long("--seed", 1L),
      think = o.int("--think", 5),
      biome = o.str("--biome", Scenario.DefaultBiome),
      towers = o.values.get("--towers").map(_.split(",").toVector.map(_.trim)).getOrElse(Vector.empty)
    )
    val global = Balance.GlobalSteps(o.dbl("--global-hp", 1.1), o.dbl("--global-speed", 1.03))
    val pass   = o.str("--pass", "both")
    if !Set("global", "local", "both").contains(pass) then
      Opts.fail(s"--pass expects global, local or both, got '$pass'", BalanceUsage)
    val levels = levelsOf(o.str("--level", "all"), BalanceUsage, settings.biome)
    val out    = o.str("--out", "data/balance/config.json")
    val sink   = progressSink(settings.biome, "balance-")
    val afterGlobal =
      if pass == "local" then Config.game
      else Balance.runGlobal(Config.game, settings, global, levels, progressSink(settings.biome, "balance-global-"), println)
    val tuned =
      if pass == "global" then afterGlobal
      else Balance.runAll(afterGlobal, settings, levels, sink, println)
    val abs    = Paths.get(out).toAbsolutePath.normalize.toString
    Option(Paths.get(out).getParent).foreach(Files.createDirectories(_))
    ConfigWriter.write(tuned, out) match
      case Left(err) => Opts.fail(s"could not write $abs: $err", BalanceUsage)
      case Right(_)  => println(s"wrote balanced config to $abs")
    println(s"play it with: headless bench --config $abs --bot spender")

  def stage(args: List[String]): Unit =
    val o = Opts.parse(args, StageUsage,
      valued = Set("--level", "--rounds", "--games", "--combo-games", "--first-low", "--lives-low", "--lives-high", "--win-bar",
        "--good-share", "--promote", "--resize", "--seed", "--think", "--biome", "--out", "--config", "--record"))
    loadConfig(o)
    val s = Stages.Settings(
      rounds = o.int("--rounds", 12),
      games = o.int("--games", 100),
      comboGames = o.int("--combo-games", 20),
      firstLow = o.dbl("--first-low", 0.6),
      livesLow = o.dbl("--lives-low", 1.0),
      livesHigh = o.dbl("--lives-high", 3.0),
      goodShare = o.dbl("--good-share", 0.3),
      winBar = o.dbl("--win-bar", 0.5),
      steps = Balance.Steps(promoteShare = o.dbl("--promote", 0.25), resizeShare = o.dbl("--resize", 0.1)),
      seed = o.long("--seed", 1L),
      think = o.int("--think", 5),
      biome = o.str("--biome", Scenario.DefaultBiome)
    )
    val count  = Config.withBiome(s.biome)(Config.levelCount)
    val levels = o.str("--level", "1,2").split(",").toVector.map { t =>
      t.trim.toIntOption.filter(n => n >= 1 && n <= count).map(_ - 1)
        .getOrElse(Opts.fail(s"--level expects level numbers 1..$count, got '$t'", StageUsage))
    }
    val out  = Paths.get(o.str("--out", "data/balance/config.json")).toAbsolutePath.normalize
    val sink: Training.Sink = (phase, level, g) => recordGame(phase, "spender", s.biome, level, g)
    val tuned = levels.foldLeft(Config.game) { (cfg, level) =>
      val (kept, results) = Stages.run(cfg, level, s, sink, println)
      val csv = out.resolveSibling(s"stage-L${level + 1}.csv").toString
      Stages.writeCsv(csv, level, results)
      println(s"level ${level + 1}  kept waves ${kept.levels(level).waves.map(_.count).mkString("/")}; " +
        s"per-option results in $csv")
      recordStage(kept, level, s, results, o.int("--record", 2))
      kept
    }
    Option(out.getParent).foreach(Files.createDirectories(_))
    ConfigWriter.write(tuned, out.toString) match
      case Left(err) => Opts.fail(s"could not write $out: $err", StageUsage)
      case Right(_)  => println(s"wrote balanced config to $out")

  /** Saves replays of a balanced level, played on the kept config: the `n` best
    * and worst plain runs on level 1; on later levels the best game of the best
    * pick for each unlock, and the worst game with no unlock.
    */
  private def recordStage(cfg: GameConfig, level: Int, s: Stages.Settings, results: Vector[Stages.Result], n: Int): Unit =
    if n > 0 then
      Config.use(cfg)
      val hash    = ReplayStore.saveConfig(ReplayStore.DefaultDir, cfg)
      val count   = if level == 0 then s.games else s.comboGames
      val enemies = Scenario.enemyCount(s.biome, level)
      def save(g: Scenario.Game, name: String): Unit =
        ReplayStore.write(ReplayStore.DefaultDir, name, Replay(
          kind = "scenario", bot = "spender", seed = g.seed, biome = s.biome, level = level, think = s.think,
          setup = g.setup, config = hash, result = g.result, steps = g.outcome.steps, commands = g.commands,
          recordedAt = System.currentTimeMillis))
      val ranked = (o: Stages.Entry) =>
        Stages.games(o, level, count, s).sortBy(g => -Training.score(g.outcome, enemies))
      val prefix = s"stage-L${level + 1}"
      if level == 0 then
        val games = ranked(results.head.option)
        games.take(n).zipWithIndex.foreach((g, i) => save(g, s"$prefix-best-${i + 1}"))
        games.takeRight(n).reverse.zipWithIndex.foreach((g, i) => save(g, s"$prefix-worst-${i + 1}"))
      else
        def slug(t: String) = t.toLowerCase.replaceAll("[^a-z0-9]+", "-")
        results.groupBy(_.option.unlock).foreach { (unlock, rs) =>
          val top = rs.maxBy(r => (r.stats.winRate, -r.stats.livesLostWon))
          val tag = slug(unlock.getOrElse("no-unlock") + "-" + Stages.pickLabel(top.option))
          ranked(top.option).headOption.foreach(save(_, s"$prefix-$tag-best"))
        }
        results.filter(_.option.unlock.isEmpty).minByOption(_.stats.winRate)
          .flatMap(r => ranked(r.option).lastOption)
          .foreach(save(_, s"$prefix-no-unlock-worst"))
      println(s"  saved replays of level ${level + 1} to ${ReplayStore.DefaultDir} ($prefix-*)")

  def genLevels(args: List[String]): Unit =
    val o = Opts.parse(args, GenUsage, valued = Set("--count", "--size", "--shape", "--biome", "--biome-name", "--seed", "--into"))
    val into  = o.str("--into", "core/src/main/resources/config.json")
    val size  = o.int("--size", 0) match
      case 0 => ConfigLoader.load(into).fold(err => Opts.fail(err, GenUsage), _.board.gridSize)
      case n => n
    val shape = o.str("--shape", "mixed") match
      case "mixed"                              => LevelGen.Shapes
      case s if LevelGen.Shapes.contains(s)     => Vector(s)
      case other => Opts.fail(s"--shape expects snake, loop, fork or mixed, got '$other'", GenUsage)
    val biome = o.values.get("--biome").map(id => (id, o.str("--biome-name", id)))
    LevelGen.writeInto(into, o.int("--count", 5), size, o.long("--seed", 42L), shape, biome) match
      case Left(err) => Opts.fail(s"could not add levels to $into: $err", GenUsage)
      case Right(n)  => println(s"added $n generated levels (${size}x$size) to $into")

  def replay(args: List[String]): Unit =
    args match
      case ("--help" | "-h") :: _ =>
        println(ReplayUsage)
        throw CommandDone
      case path :: Nil =>
        val text = Files.readString(Paths.get(path))
        Replay.parse(text) match
          case Left(err) => Opts.fail(err, ReplayUsage)
          case Right(r) =>
            ReplayStore.loadConfig(ReplayStore.DefaultDir, r.config) match
              case Left(err) => Opts.fail(err, ReplayUsage)
              case Right(cfg) =>
                Config.use(cfg)
                val got = Replay.verify(r)
                println(s"recorded  ${r.result}")
                println(s"replayed  $got")
                println(if got == r.result then "match: yes" else "match: NO")
      case _ => Opts.fail("expected one replay file", ReplayUsage)

  /** Saves the `n` best and `n` worst games (by [[Training.score]]) as replays. */
  private def saveExtremes(
      bot: String, biome: String, level: Int, think: Int, hash: String,
      played: Vector[Scenario.Game], enemies: Int, n: Int
  ): Unit =
    val ranked = played.sortBy(g => -Training.score(g.outcome, enemies))
    val best   = ranked.take(n).zipWithIndex
    val worst  = ranked.takeRight(n).reverse.zipWithIndex
    def save(g: Scenario.Game, tag: String, rank: Int): Unit =
      val played = if g.setup.rolled then RunGen.roll(g.seed).biome else biome
      val r = Replay(
        kind = "scenario", bot = bot, seed = g.seed, biome = played, level = level, think = think, setup = g.setup,
        config = hash, result = g.result, steps = g.outcome.steps, commands = g.commands,
        recordedAt = System.currentTimeMillis
      )
      val where = if g.setup.rolled then "rolled" else biome
      ReplayStore.write(ReplayStore.DefaultDir, s"$bot-$where-L${level + 1}-$tag-${rank + 1}", r)
    best.foreach { case (g, i) => save(g, "best", i) }
    worst.foreach { case (g, i) => save(g, "worst", i) }
    println(s"  saved ${best.size} best and ${worst.size} worst replays to ${ReplayStore.DefaultDir}")

  private def recordGame(phase: String, bot: String, biome: String, level: Int, g: Scenario.Game): Unit =
    val o = g.outcome
    ProgressLog.append(ProgressLog.DefaultPath, ProgressLog.line(
      phase, bot, level, biome, g.seed, ProgressLog.outcomeOf(o.won, o.timedOut),
      o.kills, o.leaks, o.livesLeft, o.goldLeft, o.goldSpent, o.towersBuilt, o.wavesStarted,
      o.steps * Config.MaxStepDt, g.wallMs, g.marks
    ))

  /** Logs explorer games from training and balancing to the progress file. */
  private def progressSink(biome: String, prefix: String = "", bot: String = "explorer"): Training.Sink =
    (phase, level, g) => recordGame(prefix + phase, bot, biome, level, g)

  /** The progress file, summarized per phase, bot and level. */
  def progress(args: List[String]): Unit =
    val o = Opts.parse(args, ProgressUsage, valued = Set("--phase", "--bot", "--file"))
    val file = o.str("--file", ProgressLog.DefaultPath)
    ProgressLog.summarize(file, o.values.get("--phase"), o.values.get("--bot")) match
      case Left(err) => Opts.fail(err, ProgressUsage)
      case Right(rows) =>
        println("phase          bot       biome            level  games  won%   kills  leaks  waves  secs_won  secs_lost  wall_ms")
        rows.foreach { r =>
          println(
            f"${r.phase}%-14s ${r.bot}%-9s ${r.biome}%-16s L${r.level + 1}%-5d ${r.games}%5d  ${100.0 * r.won / r.games}%5.1f  " +
              f"${r.kills}%5.1f  ${r.leaks}%5.1f  ${r.waves}%5.1f  ${r.secondsWon}%8.1f  ${r.secondsLost}%9.1f  ${r.wallMs}%7.1f"
          )
        }
        val games = rows.map(_.games).sum
        val realSeconds = rows.map(r => r.games * r.wallMs).sum / 1000.0
        println(f"total: $games%d games, $realSeconds%.0f s of game-simulation time (real time, summed over games)")

  private val ProgressUsage =
    """usage: headless progress [options]
      |  --phase <name>       only this phase (bench, train, test, balance-train, balance-test, campaign)
      |  --bot <name>         only this bot
      |  --file <path>        progress log (default data/progress/games.csv)""".stripMargin

  private def levelsOf(spec: String, usage: String, biome: String): Vector[Int] =
    val count = Config.withBiome(biome)(Config.levelCount)
    if spec == "all" then (0 until count).toVector
    else
      spec.toIntOption.filter(n => n >= 1 && n <= count) match
        case Some(n) => Vector(n - 1)
        case None    => Opts.fail(s"--level expects 1..$count or all, got '$spec'", usage)

  private def levelName(biome: String, level: Int): String =
    Config.withBiome(biome)(Config.levelAt(level).name)

  private def loadConfig(o: Opts): Unit =
    o.values.get("--config").foreach { path =>
      ConfigLoader.load(path) match
        case Left(err) => Opts.fail(s"config error: $err", "")
        case Right(_)  => Config.load(path)
    }

  /** Parsed `--key value` pairs and bare flags for one command. */
  private final class Opts(val values: Map[String, String], val flags: Set[String]):
    def str(key: String, default: String): String = values.getOrElse(key, default)
    def flag(name: String): Boolean = flags.contains(name)

    def int(key: String, default: Int): Int =
      values.get(key) match
        case None    => default
        case Some(v) => v.toIntOption.getOrElse(Opts.fail(s"$key expects an integer, got '$v'", ""))

    def long(key: String, default: Long): Long =
      values.get(key) match
        case None    => default
        case Some(v) => v.toLongOption.getOrElse(Opts.fail(s"$key expects an integer, got '$v'", ""))

    def dbl(key: String, default: Double): Double =
      values.get(key) match
        case None    => default
        case Some(v) => v.toDoubleOption.getOrElse(Opts.fail(s"$key expects a number, got '$v'", ""))

  private object Opts:
    def parse(args: List[String], usage: String, valued: Set[String], flagged: Set[String] = Set.empty): Opts =
      if args.contains("--help") || args.contains("-h") then
        println(usage)
        throw CommandDone
      def loop(rest: List[String], values: Map[String, String], flags: Set[String]): Opts =
        rest match
          case Nil => Opts(values, flags)
          case f :: t if flagged.contains(f) => loop(t, values, flags + f)
          case k :: v :: t if valued.contains(k) => loop(t, values + (k -> v), flags)
          case k :: Nil if valued.contains(k) => fail(s"$k requires a value", usage)
          case other :: _ => fail(s"unknown argument '$other'", usage)
      loop(args, Map.empty, Set.empty)

    def fail(msg: String, usage: String): Nothing =
      throw CommandError(msg, usage)
