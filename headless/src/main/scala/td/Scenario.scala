package td

/** One headless game on a single level: a fresh run in one biome, no talents or
  * relics, started at `level` (0-based). It ends when the level is cleared (the
  * draft opens), the core is lost, or the step cap is hit. It never touches the
  * journal or meta save files, so thousands of games leave nothing behind.
  */
object Scenario:

  val DefaultBiome: String = "front-lawn"

  /** How one game ended. `goldSpent` is the cost of every tower the player built. */
  final case class Outcome(
      won: Boolean,
      livesStart: Int,
      livesLeft: Int,
      goldLeft: Int,
      goldSpent: Int,
      towersBuilt: Int,
      kills: Int,
      wavesStarted: Int,
      leaks: Int,
      steps: Int,
      timedOut: Boolean
  )

  /** A finished game plus every command the player issued, keyed by the step it
    * was applied on. That is all a replay needs.
    */
  final case class Game(
      seed: Long,
      outcome: Outcome,
      result: String,
      commands: Vector[(Int, Vector[Command])],
      marks: Vector[Mark],
      wallMs: Long,
      setup: Setup = Setup()
  )

  def enemyCount(biome: String, level: Int): Int =
    Config.withBiome(biome)(Config.levelAt(level).waves.map(_.count).sum)

  def play(
      player: Player,
      biome: String,
      level: Int,
      seed: Long,
      think: Int,
      maxSteps: Int = 400000,
      setup: Setup = Setup()
  ): Game =
    var w     = Replay.scenarioWorld(seed, biome, level, setup)
    var spent = 0
    var built = 0
    var steps = 0
    var goldAtEnd = w.gold
    val livesStart = w.lives
    val log   = Vector.newBuilder[(Int, Vector[Command])]
    val marks = Vector.newBuilder[Mark]
    val began = System.nanoTime()
    while !finished(w, level) && steps < maxSteps do
      val cmds =
        if steps % think == 0 then Config.withContext(biome, level)(player.decide(w))
        else Vector.empty
      if cmds.nonEmpty then log += (steps -> cmds)
      val next  = Sim.step(w, Config.MaxStepDt, cmds)
      val added = next.towers.drop(w.towers.size)
      spent += added.map(_.spec.cost).sum
      built += added.size
      if next.wave > w.wave then
        marks += Mark(level, next.wave, (steps + 1) * Config.MaxStepDt, next.kills, next.lives)
      // A skipped draft moves straight on and resets gold; report the level's own gold.
      if next.level == level then goldAtEnd = next.gold
      w = next.copy(events = Vector.empty, hitFeedback = Vector.empty, nameEvents = Vector.empty)
      steps += 1
    val outcome = Outcome(
      won = !w.isOver && (w.won || w.draft.isDefined || w.level > level),
      livesStart = livesStart,
      livesLeft = w.lives,
      goldLeft = goldAtEnd,
      goldSpent = spent,
      towersBuilt = built,
      kills = w.kills,
      wavesStarted = w.wave,
      leaks = w.leaks,
      steps = steps,
      timedOut = !finished(w, level)
    )
    val wallMs = (System.nanoTime() - began) / 1000000L
    Game(seed, outcome, Replay.resultOf(w, steps), log.result(), marks.result(), wallMs, setup)

  /** Over, won, or the level was cleared: the draft opened, or was skipped and
    * the run moved on.
    */
  private def finished(w: World, level: Int): Boolean =
    w.isOver || w.won || w.draft.isDefined || w.level > level
