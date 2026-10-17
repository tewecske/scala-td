package td

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*

/** A point on a game's timeline: a wave started, at this game time, with these
  * kills and lives so far.
  */
final case class Mark(level: Int, wave: Int, seconds: Double, kills: Int, lives: Int)

/** One row per finished game in `data/progress/games.csv`: which bot, which level,
  * how it ended, how far it got, and how long the game took in game time and in
  * real time. The `timeline` column has one entry per wave start.
  */
object ProgressLog:
  def DefaultPath: String = DataDir.path("progress/games.csv")

  val Header: String =
    "phase,bot,level,biome,seed,outcome,kills,leaks,lives_left,gold_left,gold_spent,towers,waves_started,game_seconds,wall_ms,timeline"

  def line(
      phase: String,
      bot: String,
      level: Int,
      biome: String,
      seed: Long,
      outcome: String,
      kills: Int,
      leaks: Int,
      lives: Int,
      gold: Int,
      spent: Int,
      towers: Int,
      waves: Int,
      seconds: Double,
      wallMs: Long,
      marks: Vector[Mark]
  ): String =
    val timeline = marks.map(m => s"L${m.level + 1}W${m.wave}:${"%.1f".format(m.seconds)}:${m.kills}:${m.lives}").mkString("|")
    List(
      phase, bot, (level + 1).toString, biome, seed.toString, outcome,
      kills.toString, leaks.toString, lives.toString, gold.toString, spent.toString, towers.toString,
      waves.toString, "%.2f".format(seconds), wallMs.toString, timeline
    ).mkString(",")

  def outcomeOf(won: Boolean, timedOut: Boolean): String =
    if won then "won" else if timedOut then "timeout" else "lost"

  /** Appends one row, creating the file with a header first. */
  def append(path: String, row: String): Unit =
    val p = Paths.get(path)
    Option(p.getParent).foreach(Files.createDirectories(_))
    if !Files.isRegularFile(p) then Files.writeString(p, Header + "\n", StandardCharsets.UTF_8)
    Files.writeString(p, row + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND)

  /** One aggregated line: all games of one bot on one level in one phase. */
  final case class Summary(
      phase: String,
      bot: String,
      biome: String,
      level: Int,
      games: Int,
      won: Int,
      kills: Double,
      leaks: Double,
      waves: Double,
      secondsWon: Double,
      secondsLost: Double,
      wallMs: Double
  )

  def summarize(path: String, phase: Option[String], bot: Option[String]): Either[String, Vector[Summary]] =
    val p = Paths.get(path)
    if !Files.isRegularFile(p) then Left(s"no progress log at $path yet")
    else
      val rows = Files.readAllLines(p, StandardCharsets.UTF_8).asScala.toVector.drop(1)
        .filter(_.nonEmpty).map(_.split(",", -1).toVector)
        .filter(f => phase.forall(_ == f(0)) && bot.forall(_ == f(1)))
      val groups = rows.groupBy(f => (f(0), f(1), f(3), f(2).toInt))
      Right(groups.toVector.sortBy { case ((ph, b, bi, l), _) => (ph, b, bi, l) }.map { case ((ph, b, bi, l), fs) =>
        def mean(i: Int): Double = if fs.isEmpty then 0.0 else fs.map(_(i).toDouble).sum / fs.size
        val won  = fs.filter(_(5) == "won")
        val lost = fs.filterNot(_(5) == "won")
        def meanSec(xs: Vector[Vector[String]]): Double =
          if xs.isEmpty then 0.0 else xs.map(_(13).toDouble).sum / xs.size
        Summary(
          phase = ph, bot = b, biome = bi, level = l - 1, games = fs.size, won = won.size,
          kills = mean(6), leaks = mean(7), waves = mean(12),
          secondsWon = meanSec(won), secondsLost = meanSec(lost), wallMs = mean(14)
        )
      })
