package td

/** An unattended walk through every screen that saves a screenshot of each,
  * for checking the look at a given resolution without clicking around. Start
  * the game with `TD_TOUR=<dir>`; it exits when the last shot is written.
  */
object Tour:

  /** One stop: the screen to show and how many frames to let it settle. */
  final case class Step(name: String, phase: () => Phase, frames: Int = 8, action: () => Unit = () => ())

  def steps: Vector[Step] =
    if sys.env.contains("TD_TOUR_SHORT") then Vector(Step("menu", () => Phase.Menu, frames = 10))
    else if sys.env.contains("TD_TOUR_DISPLAY") then
      // switches the live window between modes, as the settings screen's APPLY does
      Vector(
        Step("start", () => Phase.Menu, frames = 10),
        Step("windowed-1280x720", () => Phase.Menu, frames = 30,
          action = () => Display.apply(DisplaySettings(WindowMode.Windowed, 1280, 720))),
        Step("borderless", () => Phase.Menu, frames = 30,
          action = () => Display.apply(DisplaySettings(WindowMode.Borderless))),
        Step("windowed-1600x900", () => Phase.Settings(DisplaySettings(), Phase.Menu), frames = 30,
          action = () => Display.apply(DisplaySettings(WindowMode.Windowed, 1600, 900))))
    else Vector(
      Step("menu", () => Phase.Menu, frames = 30),
      Step("settings", () => Phase.Settings(DisplaySettings(), Phase.Menu)),
      Step("talents", () => Phase.Talents(MetaData(12, Vector("filler-damage", "filler-lives")))),
      Step("briefing", () => Phase.Briefing(Sim.initial(RunGen.roll(7L)))),
      Step("loadout", () => Phase.Loadout(RunGen.roll(7L), Vector("Cuboid"), MetaData(35, Vector.empty, bestCleared = 1))),
      Step("game-10x10", () => Phase.Playing(battle(level = 0, steps = 900)), frames = 4),
      Step("game-14x14", () => Phase.Playing(battle(level = 5, steps = 1300)), frames = 40),
      Step("paused", () => Phase.Paused(battle(level = 0, steps = 900))),
      Step("draft", () => Phase.Playing(draft)),
      Step("recap", () => Phase.Recap(false, 1, 3, 0, 40, Vector.empty, Vector.empty, None)),
      // last: playing a replay switches to the config it was recorded with
      Step("replays", () => Phase.ReplayList(ReplayStore.list(ReplayStore.DefaultDir), 0)),
      Step("replaying", () => replaying, frames = 20)
    )

  /** A level mid-fight: towers on the cells that cover the most road, then
    * `steps` simulation steps so enemies, shots and damage numbers are on screen.
    */
  def battle(level: Int, steps: Int): World =
    val run = RunGen.roll(11L).copy(loadout = Vector("Cuboid", "Matchbox", "Ice Cube"))
    val w0  = Sim.initial(run)
    val w1  = w0.copy(level = level, gold = 600)
    Config.setActive(w1.biome, level)
    val path  = Grid.pathCells(level)
    val n     = Config.gridSizeOf(level)
    val cells = for x <- 0 until n; y <- 0 until n yield GridPos(x, y)
    def cover(c: GridPos) = path.count(p => math.abs(p.x - c.x) <= 2 && math.abs(p.y - c.y) <= 2)
    val best = cells.filter(Grid.isBuildable(_, level)).sortBy(c => -cover(c)).take(7)
    val kinds = Vector("Cuboid", "Matchbox", "Cuboid", "Ice Cube", "Cuboid", "Matchbox", "Cuboid")
    val built = best.zip(kinds).foldLeft(w1) { case (w, (c, k)) => Sim.step(w, 0f, Vector(Command.Build(c, k))) }
    Iterator.iterate(built)(w => Sim.step(w, Config.MaxStepDt, Vector.empty)
      .copy(events = Vector.empty, hitFeedback = Vector.empty, nameEvents = Vector.empty)).drop(steps).next()

  /** The newest recording, halfway through, at 4x. */
  def replaying: Phase =
    ReplayStore.list(ReplayStore.DefaultDir).headOption.flatMap { (_, r) =>
      ReplayStore.loadConfig(ReplayStore.DefaultDir, r.config).toOption.map { cfg =>
        Config.use(cfg)
        Config.clearActiveBiome()
        Grid.reset()
        Phase.Replaying(Playback.start(r).seek(r.steps / 2).copy(speed = 4.0))
      }
    }.getOrElse(Phase.Menu)

  /** The draft between levels 1 and 2: a relic and two augments. */
  def draft: World =
    val w = Sim.initial(RunGen.roll(5L))
    val offers = Vector(DraftOffer.Relic("square-deal"), DraftOffer.Augment("vertigo"), DraftOffer.Augment("sharpened-edge"))
      .filter {
        case DraftOffer.Relic(id)   => Config.relics.exists(_.id == id)
        case DraftOffer.Augment(id) => Config.augments.exists(_.id == id)
      }
    w.copy(draft = Some(Draft(if offers.nonEmpty then offers else w.run.draftPool.take(Config.OfferCount), Config.RerollCost)))
