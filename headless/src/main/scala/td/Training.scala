package td

import scala.util.Random

/** Aggregate numbers over a set of games on one level. `livesLostWon` and
  * `goldLeftWon` only average the games that were won.
  */
final case class Stats(
    games: Int,
    wins: Int,
    livesLostWon: Double,
    goldLeftWon: Double,
    goldSpent: Double,
    towers: Double,
    steps: Double
):
  def winRate: Double = if games == 0 then 0.0 else wins.toDouble / games

  def line: String =
    s"wins=$wins/$games (${Stats.fmt(100 * winRate)}%)  " +
      s"livesLostWhenWon=${Stats.fmt(livesLostWon)}  goldLeftWhenWon=${Stats.fmt(goldLeftWon)}  " +
      s"goldSpent=${Stats.fmt(goldSpent)}  towers=${Stats.fmt(towers)}  steps=${Stats.fmt(steps)}"

object Stats:
  def fmt(x: Double): String = "%.1f".format(x)

  def of(outcomes: Vector[Scenario.Outcome]): Stats =
    val won = outcomes.filter(_.won)
    def mean(xs: Vector[Double]): Double = if xs.isEmpty then 0.0 else xs.sum / xs.size
    Stats(
      games = outcomes.size,
      wins = won.size,
      livesLostWon = mean(won.map(o => (o.livesStart - o.livesLeft).toDouble)),
      goldLeftWon = mean(won.map(_.goldLeft.toDouble)),
      goldSpent = mean(outcomes.map(_.goldSpent.toDouble)),
      towers = mean(outcomes.map(_.towersBuilt.toDouble)),
      steps = mean(outcomes.map(_.steps.toDouble))
    )

object Training:

  /** Receives every game played: the phase it belongs to, the level, and the game. */
  type Sink = (String, Int, Scenario.Game) => Unit

  val EpsilonStart: Double = 1.0
  val EpsilonDecay: Double = 0.8
  val EpsilonFloor: Double = 0.05

  /** Exploration share for a batch index: random at first, then mostly learned. */
  def epsilonAt(batch: Int): Double =
    math.max(EpsilonFloor, EpsilonStart * math.pow(EpsilonDecay, batch.toDouble))

  /** What the explorer is trying to maximise: winning matters most, then how much
    * of the wave pool was killed, then lives left. Losses still get partial credit
    * so the learner can climb toward a win.
    */
  def score(o: Scenario.Outcome, enemies: Int): Double =
    (if o.won then 100.0 else 0.0) + 50.0 * o.kills / math.max(1, enemies) + 2.0 * o.livesLeft

  /** Plays `batches` batches of `batchSize` games on `level`, learning after each
    * batch. Seeds continue from what the knowledge has already seen, so resumed
    * training never replays old games.
    */
  def train(
      start: Knowledge,
      batches: Int,
      batchSize: Int,
      seed: Long,
      think: Int,
      biome: String,
      level: Int,
      towers: Vector[String],
      sink: Sink,
      log: String => Unit
  ): Knowledge =
    val enemies = Scenario.enemyCount(biome, level)
    (0 until batches).foldLeft(start) { (k, _) =>
      val eps   = epsilonAt(k.batches)
      val first = seed + k.batches.toLong * batchSize
      val games = (0 until batchSize).toVector.map { i =>
        val s  = first + i
        val ex = new Explorer(k, new Random(s), eps)
        (Scenario.play(ex, biome, level, s, think, setup = Setup(loadout = towers)), ex.trace)
      }
      games.foreach { case (g, _) => sink("train", level, g) }
      val next = Knowledge.learn(k, games.map { case (g, t) => score(g.outcome, enemies) -> t })
      log(
        s"batch ${next.batches}  eps=${Stats.fmt(eps)}  " +
          s"${Stats.of(games.map(_._1.outcome)).line}  deadCells=${next.rejected.size}"
      )
      next
    }

  /** Plays greedily (no exploration) from `k`; nothing is learned. */
  def evaluate(
      k: Knowledge, games: Int, seed: Long, think: Int, biome: String, level: Int,
      towers: Vector[String] = Vector.empty
  ): Vector[Scenario.Game] =
    (0 until games).toVector.map { i =>
      val s = seed + i
      Scenario.play(new Explorer(k, new Random(s), 0.0), biome, level, s, think, setup = Setup(loadout = towers))
    }
