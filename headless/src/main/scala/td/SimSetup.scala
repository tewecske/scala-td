package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try

/** A point to start simulations from, saved as JSON: which config, bot and level,
  * and what the player has by then (unlocked towers, talents, draft picks, lives
  * already lost). `scripts/sim.sh setup <file>` and the simulator window run it.
  *
  * `level` is 1-based. `biome` is a biome id or [[SimSetup.Rolled]] (each game rolls
  * its biome and twist the way the game does). When more towers are unlocked than a
  * run may bring, `everyLoadout` plays every allowed choice of them; otherwise the
  * run brings all of them.
  */
final case class SimSetup(
    name: String = "",
    config: String = "",
    bot: String = "builder",
    biome: String = SimSetup.Rolled,
    level: Int = 2,
    games: Int = 50,
    seed: Long = 1L,
    think: Int = 5,
    livesLost: Int = 0,
    unlocked: Vector[String] = Vector.empty,
    everyLoadout: Boolean = true,
    talents: Vector[String] = Vector.empty,
    relics: Vector[String] = Vector.empty,
    augment: Option[String] = None
)

object SimSetup:
  val Rolled = "rolled"
  val DefaultDir = "data/sims"

  /** Bots that play without training first. */
  val Bots: Vector[String] = Vector("builder", "spender", "naive")

  final case class Result(loadout: Vector[String], stats: Stats)

  /** The config a setup plays: its own file, else `./config.json`, else the bundled one. */
  def loadConfig(s: SimSetup): Either[String, GameConfig] =
    if s.config.nonEmpty then ConfigLoader.load(s.config) else ConfigLoader.load()

  /** Talents plus every prerequisite they need, prerequisites first. */
  def withPrereqs(cfg: GameConfig, ids: Vector[String]): Vector[String] =
    def chain(id: String): Vector[String] =
      cfg.talents.find(_.id == id).fold(Vector.empty)(t => (t.prereqs.flatMap(chain) :+ id).distinct)
    ids.flatMap(chain).distinct

  /** The loadouts to play: the unlocked towers, or every allowed choice of them. */
  def loadouts(cfg: GameConfig, s: SimSetup): Vector[Vector[String]] =
    val unlocked = cfg.towers.map(_.name).filter(s.unlocked.contains)
    val max      = cfg.loadout.maxSelected
    if unlocked.isEmpty then Vector(cfg.loadout.defaultSelected)
    else if unlocked.size <= max || !s.everyLoadout then Vector(unlocked)
    else unlocked.combinations(max).toVector

  /** Things a real player could not have at this point; the games still run. */
  def warnings(cfg: GameConfig, s: SimSetup): Vector[String] =
    s.unlocked.flatMap { t =>
      val needs = cfg.loadout.levelsToUnlock(t)
      Option.when(needs > s.level - 1)(s"$t unlocks after clearing level $needs; a player starting level ${s.level} " +
        s"has cleared at most ${s.level - 1}")
    }

  /** What is wrong with a setup for `cfg`, if anything. */
  def problems(cfg: GameConfig, s: SimSetup): Vector[String] =
    val levels = Config.withBiome(if s.biome == Rolled then Scenario.DefaultBiome else s.biome)(Config.levelCount)
    Vector(
      Option.when(!Bots.contains(s.bot))(s"unknown bot '${s.bot}' (want ${Bots.mkString(", ")})"),
      Option.when(s.biome != Rolled && !cfg.biomes.exists(_.id == s.biome))(s"unknown biome '${s.biome}'"),
      Option.when(s.level < 1 || s.level > levels)(s"level ${s.level} is not between 1 and $levels"),
      Option.when(s.games < 1)("games must be at least 1")
    ).flatten ++
      s.unlocked.filterNot(n => cfg.towers.exists(_.name == n)).map(n => s"unknown tower '$n'") ++
      s.talents.filterNot(id => cfg.talents.exists(_.id == id)).map(id => s"unknown talent '$id'") ++
      s.relics.filterNot(id => cfg.relics.exists(_.id == id)).map(id => s"unknown relic '$id'") ++
      s.augment.filterNot(id => cfg.augments.exists(_.id == id)).map(id => s"unknown augment '$id'")

  /** Plays the setup: every loadout, `games` games each. `progress(done, total)` is
    * called after every game and `result` after every loadout; `stop` is checked
    * between games. Uses (and leaves) `cfg` as the active config.
    */
  def run(cfg: GameConfig, s: SimSetup, progress: (Int, Int) => Unit = (_, _) => (),
          result: Result => Unit = _ => (), stop: () => Boolean = () => false): Vector[Result] =
    Config.use(cfg)
    val talents = withPrereqs(cfg, s.talents)
    val all     = loadouts(cfg, s)
    val total   = all.size * s.games
    val biome   = if s.biome == Rolled then Scenario.DefaultBiome else s.biome
    var done    = 0
    all.flatMap { loadout =>
      val setup = Setup(loadout = loadout, talents = talents, relics = s.relics, augment = s.augment,
        livesLost = s.livesLost, rolled = s.biome == Rolled)
      val played = (0 until s.games).toVector.flatMap { i =>
        if stop() then None
        else
          val seed = s.seed + i
          val p    = Players.create(s.bot, Knowledge(), seed).fold(e => throw IllegalArgumentException(e), identity)
          val g    = Scenario.play(p, biome, s.level - 1, seed, s.think, setup = setup)
          done += 1
          progress(done, total)
          Some(g.outcome)
      }
      Option.when(played.nonEmpty) {
        val r = Result(loadout, Stats.of(played))
        result(r)
        r
      }
    }

  // -- JSON ------------------------------------------------------------------------

  def load(path: String): Either[String, SimSetup] =
    Try(Files.readString(Paths.get(path), StandardCharsets.UTF_8)).toEither.left.map(_.getMessage).flatMap(parse)

  def save(path: String, s: SimSetup): Either[String, Unit] =
    Try {
      val p = Paths.get(path)
      Option(p.getParent).foreach(Files.createDirectories(_))
      Files.writeString(p, toJson(s) + "\n", StandardCharsets.UTF_8)
      ()
    }.toEither.left.map(_.getMessage)

  def toJson(s: SimSetup): String =
    val o = JsonValue(JsonValue.ValueType.`object`)
    def strs(v: Vector[String]): JsonValue =
      val a = JsonValue(JsonValue.ValueType.array)
      v.foreach(x => a.addChild(JsonValue(x)))
      a
    o.addChild("name", JsonValue(s.name))
    o.addChild("config", JsonValue(s.config))
    o.addChild("bot", JsonValue(s.bot))
    o.addChild("biome", JsonValue(s.biome))
    o.addChild("level", JsonValue(s.level.toLong))
    o.addChild("games", JsonValue(s.games.toLong))
    o.addChild("seed", JsonValue(s.seed))
    o.addChild("think", JsonValue(s.think.toLong))
    o.addChild("livesLost", JsonValue(s.livesLost.toLong))
    o.addChild("unlocked", strs(s.unlocked))
    o.addChild("everyLoadout", JsonValue(s.everyLoadout))
    o.addChild("talents", strs(s.talents))
    o.addChild("relics", strs(s.relics))
    o.addChild("augment", s.augment.fold(JsonValue(JsonValue.ValueType.nullValue))(JsonValue(_)))
    o.prettyPrint(JsonWriter.OutputType.json, 2)

  def parse(text: String): Either[String, SimSetup] =
    Try {
      val r = JsonReader().parse(text)
      val d = SimSetup()
      def strs(name: String): Vector[String] =
        Option(r.get(name)).filter(_.isArray).fold(Vector.empty)(a => (0 until a.size).map(a.get(_).asString()).toVector)
      SimSetup(
        name = r.getString("name", d.name),
        config = r.getString("config", d.config),
        bot = r.getString("bot", d.bot),
        biome = r.getString("biome", d.biome),
        level = r.getInt("level", d.level),
        games = r.getInt("games", d.games),
        seed = r.getLong("seed", d.seed),
        think = r.getInt("think", d.think),
        livesLost = r.getInt("livesLost", d.livesLost),
        unlocked = strs("unlocked"),
        everyLoadout = r.getBoolean("everyLoadout", d.everyLoadout),
        talents = strs("talents"),
        relics = strs("relics"),
        augment = Option(r.get("augment")).filterNot(_.isNull).map(_.asString())
      )
    }.toEither.left.map(e => s"bad setup file: ${e.getMessage}")
