package td

import scala.jdk.CollectionConverters.*

object HeadlessMain:

  private val DefaultMaxSteps = 1000000L

  private case class Options(
      seed: Long = RunContext.DefaultSeed,
      config: Option[String] = None,
      maxLevels: Int = Int.MaxValue,
      maxSteps: Long = DefaultMaxSteps,
      bot: String = "naive",
      think: Option[Int] = None,
      record: Boolean = false
  )

  private val usage =
    """usage: headless [command] [options]
      |
      |commands:
      |  run [options]          one full game through all levels (default)
      |  bench [options]        play each level many times with one bot, print stats
      |  train [options]        train the explorer on each level, save its knowledge
      |  balance [options]      retune levels until the explorer stops beating them easily
      |  stage [options]        balance levels one at a time against the spender, with every
      |                         unlock and draft pick a player could have by then
      |  gen-levels [options]   add random levels to the config
      |  replay <file>          check a saved game replays exactly
      |  progress [options]     summarize the progress log (games, wins, kills, time)
      |
      |run options:
      |  --seed <long>        run seed (default 24301)
      |  --config <path>      load config from JSON file (default bundled)
      |  --max-levels <n>     stop after reaching level index n (0-based)
      |  --max-steps <n>      hard cap on Sim.step calls (default 1000000)
      |  --bot <name>         naive | spender | explorer | explorer-fresh | none (default naive)
      |  --think <n>          bot acts every n steps (default 1 for naive, 5 otherwise)
      |  --no-bot             same as --bot none
      |  --record             save this game as a replay in data/replays
      |  --help               print this help and exit
      |
      |Run 'headless <command> --help' for the options of the other commands.""".stripMargin

  def main(args: Array[String]): Unit =
    args.toList match
      case "batch" :: file :: Nil => batch(file)
      case "repl" :: Nil          => repl()
      case _                      => if !tryRun(args.toList) then sys.exit(2)

  /** Runs one command in a fresh state: the bundled config (or the config file the
    * command names), no biome active, no cached routes. Lets many commands share one
    * JVM without one command's balance tweaks leaking into the next.
    */
  private def runOnce(args: List[String]): Unit =
    Config.reload()
    Config.clearActiveBiome()
    Grid.reset()
    dispatch(args)

  /** Returns false when the command was rejected (the error is already printed). */
  private def tryRun(args: List[String]): Boolean =
    try
      runOnce(args)
      true
    catch
      case CommandDone => true
      case e: CommandError =>
        System.err.println(s"error: ${e.msg}")
        if e.usage.nonEmpty then System.err.println(e.usage)
        false

  /** Splits a command line on spaces, keeping "double quoted text" together. */
  private def tokenize(line: String): List[String] =
    val out  = Vector.newBuilder[String]
    val cur  = new StringBuilder
    var quoted = false
    var any    = false
    line.foreach { c =>
      if c == '"' then
        quoted = !quoted
        any = true
      else if c.isWhitespace && !quoted then
        if any || cur.nonEmpty then
          out += cur.toString
          cur.clear()
          any = false
      else
        cur += c
        any = true
    }
    if any || cur.nonEmpty then out += cur.toString
    out.result().toList

  /** Runs every line of a script in this one JVM. `#` starts a comment. */
  private def batch(file: String): Unit =
    val lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(file)).asScala.toVector
      .map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#"))
    val failed = lines.count { l =>
      println(s"== $l")
      !tryRun(tokenize(l))
    }
    println(s"batch finished: ${lines.size} commands, $failed failed")
    if failed > 0 then sys.exit(1)

  /** Reads commands from stdin until `quit`. One JVM for the whole session. */
  private def repl(): Unit =
    val in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))
    println("td repl: type a command (e.g. bench --bot spender --level 1 --games 20), or 'quit'")
    var line = in.readLine()
    while line != null && line.trim != "quit" && line.trim != "exit" do
      val trimmed = line.trim
      if trimmed.nonEmpty && !trimmed.startsWith("#") then tryRun(tokenize(trimmed))
      print("td> ")
      line = in.readLine()

  private def dispatch(args: List[String]): Unit =
    args match
      case "bench" :: rest   => Experiments.bench(rest)
      case "train" :: rest   => Experiments.train(rest)
      case "balance" :: rest => Experiments.balance(rest)
      case "stage" :: rest   => Experiments.stage(rest)
      case "gen-levels" :: rest => Experiments.genLevels(rest)
      case "gen-campaign" :: rest => Experiments.genCampaign(rest)
      case "replay" :: rest  => Experiments.replay(rest)
      case "progress" :: rest => Experiments.progress(rest)
      case "run" :: rest     => runCommand(rest)
      case flag :: _ if flag.startsWith("-") => runCommand(args)
      case Nil               => runCommand(args)
      case other :: _        => throw CommandError(s"unknown command '$other'", usage)

  private def runCommand(args: List[String]): Unit =
    if args.contains("--help") || args.contains("-h") then
      println(usage)
      throw CommandDone
    parseArgs(args) match
      case Left(err)   => throw CommandError(err, usage)
      case Right(opts) => run(opts)

  private def parseArgs(args: List[String]): Either[String, Options] =
    def loop(rest: List[String], acc: Options): Either[String, Options] =
      rest match
        case Nil => Right(acc)
        case "--seed" :: v :: t =>
          v.toLongOption match
            case Some(s) => loop(t, acc.copy(seed = s))
            case None    => Left(s"invalid --seed value '$v'")
        case "--seed" :: Nil => Left("--seed requires a value")
        case "--config" :: v :: t => loop(t, acc.copy(config = Some(v)))
        case "--config" :: Nil     => Left("--config requires a value")
        case "--max-levels" :: v :: t =>
          v.toIntOption.filter(_ > 0) match
            case Some(n) => loop(t, acc.copy(maxLevels = n))
            case None    => Left(s"invalid --max-levels value '$v'")
        case "--max-levels" :: Nil => Left("--max-levels requires a value")
        case "--max-steps" :: v :: t =>
          v.toLongOption.filter(_ > 0) match
            case Some(n) => loop(t, acc.copy(maxSteps = n))
            case None    => Left(s"invalid --max-steps value '$v'")
        case "--max-steps" :: Nil => Left("--max-steps requires a value")
        case "--bot" :: v :: t => loop(t, acc.copy(bot = v))
        case "--bot" :: Nil    => Left("--bot requires a value")
        case "--think" :: v :: t =>
          v.toIntOption.filter(_ > 0) match
            case Some(n) => loop(t, acc.copy(think = Some(n)))
            case None    => Left(s"invalid --think value '$v'")
        case "--think" :: Nil => Left("--think requires a value")
        case "--no-bot" :: t => loop(t, acc.copy(bot = "none"))
        case "--record" :: t => loop(t, acc.copy(record = true))
        case other :: _ => Left(s"unknown argument '$other'")
    loop(args, Options()).flatMap { o =>
      if o.bot == "none" || Set("naive", "spender", "entrance", "explorer", "explorer-fresh").contains(o.bot) then Right(o)
      else Left(s"unknown bot '${o.bot}' (want naive, spender, explorer, explorer-fresh or none)")
    }

  private def run(opts: Options): Unit =
    opts.config.foreach { path =>
      ConfigLoader.load(path) match
        case Left(err) => throw CommandError(s"config error: $err", "")
        case Right(_)  => Config.load(path)
    }

    val dt    = Config.MaxStepDt
    val think = opts.think.getOrElse(if opts.bot == "naive" then 1 else 5)
    val hash  = if opts.record then Some(ReplayStore.saveConfig(ReplayStore.DefaultDir, Config.game)) else None
    if opts.bot != "none" then
      Players.create(opts.bot, Knowledge(), opts.seed).left.foreach(err => throw CommandError(err, usage))

    var world = Sim.initial(opts.seed)
    Journal.recordRun(world.run)
    var player: Option[Player] = None
    var playerLevel = -1
    var steps = 0L
    var done  = false
    var spent = 0
    var built = 0
    var lastLevel = -1
    var lastWave  = -1
    val marks = Vector.newBuilder[Mark]
    val log   = Vector.newBuilder[(Int, Vector[Command])]
    val began = System.nanoTime()
    while !done do
      if opts.bot != "none" && world.level != playerLevel then
        player = Some(bootPlayer(opts.bot, world.run.biome, world.level, opts.seed))
        playerLevel = world.level
      val cmds = player match
        case Some(p) if steps % think == 0 => Config.withContext(world.run.biome, world.level)(p.decide(world))
        case _                             => Vector.empty
      if cmds.nonEmpty then log += (steps.toInt -> cmds)
      val before = world
      world = Sim.step(world, dt, cmds)
      val added = world.towers.drop(before.towers.size)
      spent += added.map(_.spec.cost).sum
      built += added.size
      world.events.foreach(Journal.record)
      world = world.copy(events = Vector.empty)
      steps += 1
      if world.level != lastLevel || world.wave != lastWave then
        if world.wave > 0 then marks += Mark(world.level, world.wave, steps * dt, world.kills, world.lives)
        lastLevel = world.level
        lastWave = world.wave
      done = world.won || world.isOver || world.level >= opts.maxLevels || steps >= opts.maxSteps

    val wallMs = (System.nanoTime() - began) / 1000000L
    val won = world.won
    val outcome = ProgressLog.outcomeOf(won, timedOut = !won && !world.isOver)
    val waves = marks.result()
    ProgressLog.append(ProgressLog.DefaultPath, ProgressLog.line(
      "campaign", opts.bot, world.level, world.run.biome, opts.seed, outcome,
      world.kills, world.leaks, world.lives, world.gold, spent, built, waves.size,
      steps * dt, wallMs, waves
    ))
    hash.foreach { h =>
      val replay = Replay(
        kind = "campaign", bot = opts.bot, seed = opts.seed, biome = world.run.biome, level = 0,
        think = think, config = h, result = Replay.resultOf(world, steps.toInt), steps = steps.toInt,
        commands = log.result()
      )
      val path = ReplayStore.write(ReplayStore.DefaultDir, s"campaign-${opts.bot}-seed${opts.seed}", replay)
      println(s"saved replay $path")
    }
    println(summary(world, steps))

  /** The autoplay for one level of a campaign. The explorer uses that level's knowledge. */
  private def bootPlayer(bot: String, biome: String, level: Int, seed: Long): Player =
    val knowledge =
      if bot == "explorer" then KnowledgeStore.forLevel(biome, level)
      else Knowledge()
    Players.create(bot, knowledge, seed).fold(err => sys.error(err), identity)

  private def summary(w: World, steps: Long): String =
    Config.setActiveBiome(w.run.biome)
    val levelsCleared = if w.won then Config.levelCount else w.level
    val state = if w.won then "WON" else if w.isOver then "LOST" else "STOPPED"
    List(
      s"seed=${w.run.seed}",
      s"biome=${w.run.biome}",
      s"levels_cleared=$levelsCleared",
      s"waves_reached=${w.wave}",
      s"lives=${w.lives}",
      s"gold=${w.gold}",
      s"kills=${w.kills}",
      s"leaks=${w.leaks}",
      s"state=$state",
      s"steps=$steps"
    ).mkString("\t")
