package td

object HeadlessMain:

  private val DefaultMaxSteps = 1000000L

  private case class Options(
      seed: Long = RunContext.DefaultSeed,
      config: Option[String] = None,
      maxLevels: Int = Int.MaxValue,
      maxSteps: Long = DefaultMaxSteps,
      bot: Boolean = true
  )

  private val usage =
    """usage: headless [options]
      |  --seed <long>        run seed (default 24301)
      |  --config <path>      load config from JSON file (default bundled)
      |  --max-levels <n>     stop after reaching level index n (0-based)
      |  --max-steps <n>      hard cap on Sim.step calls (default 1000000)
      |  --no-bot             don't run the autoplay build bot
      |  --help               print this help and exit""".stripMargin

  def main(args: Array[String]): Unit =
    if args.contains("--help") || args.contains("-h") then
      println(usage)
      sys.exit(0)
    parseArgs(args.toList) match
      case Left(err) =>
        System.err.println(s"error: $err")
        System.err.println(usage)
        sys.exit(2)
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
        case "--no-bot" :: t => loop(t, acc.copy(bot = false))
        case other :: _ => Left(s"unknown argument '$other'")
    loop(args, Options())

  private def run(opts: Options): Unit =
    opts.config.foreach { path =>
      ConfigLoader.load(path) match
        case Left(err) =>
          System.err.println(s"config error: $err")
          sys.exit(1)
        case Right(_) =>
          Config.load(path)
    }

    val dt    = Config.MaxStepDt
    var world = Sim.initial(opts.seed)
    Journal.recordRun(world.run)
    var steps = 0L
    var done  = false
    while !done do
      val cmds = if opts.bot then Bot.step(world) else Vector.empty
      world = Sim.step(world, dt, cmds)
      world.events.foreach(Journal.record)
      world = world.copy(events = Vector.empty)
      steps += 1
      done = world.won || world.isOver || world.level >= opts.maxLevels || steps >= opts.maxSteps

    println(summary(world, steps))

  private def summary(w: World, steps: Long): String =
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
