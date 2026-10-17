package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.util.Try

/** A recorded game. The run setup and config are enough to rebuild the world, and
  * `commands` holds every command that was applied, keyed by the simulation step
  * it was applied on. Feeding the same commands into the same [[Sim.step]] calls
  * replays the game exactly.
  *
  * `kind` is "scenario" (one level, started fresh at `level`) or "campaign" (every
  * level from the start, rebuilt from `seed` via [[RunGen]]). `config` is the hash
  * of the config snapshot the game was played with (see [[ReplayStore]]); `result`
  * is the end state as text, so a replay can be checked against what was recorded.
  * `recordedAt` is when it was saved (epoch millis; 0 for files from before it existed).
  */
final case class Replay(
    kind: String,
    bot: String,
    seed: Long,
    biome: String,
    level: Int,
    think: Int,
    config: String,
    result: String,
    steps: Int,
    commands: Vector[(Int, Vector[Command])],
    setup: Setup = Setup(),
    recordedAt: Long = 0L
):
  /** How the game ended: WON, LOST, CLEARED (a single level beaten) or RUNNING (cut short). */
  def outcome: String = result.takeWhile(_ != ' ')

  /** One `key=value` field of the end state, e.g. `stat("lives")`. */
  def stat(key: String): Option[String] =
    result.split(' ').collectFirst { case kv if kv.startsWith(s"$key=") => kv.drop(key.length + 1) }

  /** Game time the recording covers, in seconds. */
  def seconds: Float = steps * Config.MaxStepDt

/** What a single-level game starts with besides the level itself: the towers
  * brought (empty = all), owned talents and relics, the augment picked in the
  * draft just before this level (held, waiting to be socketed), and the lives
  * already lost on earlier levels (lives carry over in a run). `rolled` plays the
  * run the game itself would roll from the seed (random biome, twist and enemy
  * speeds, see [[RunGen.roll]]) instead of the plain biome the scenario names.
  */
final case class Setup(
    loadout: Vector[String] = Vector.empty,
    talents: Vector[String] = Vector.empty,
    relics: Vector[String] = Vector.empty,
    augment: Option[String] = None,
    livesLost: Int = 0,
    rolled: Boolean = false
):
  def isEmpty: Boolean = this == Setup()

  /** Short label for logs and tables, e.g. "Matchbox | filler-damage | relic:square-deal". */
  def label: String =
    val parts = Vector(
      if loadout.isEmpty then "all towers" else loadout.mkString("+"),
      if talents.isEmpty then "no talents" else talents.mkString("+")
    ) ++ relics.map("relic:" + _) ++ augment.map("augment:" + _) ++ Option.when(rolled)("rolled run")
    parts.mkString(" | ")

object Replay:

  /** The run a single-level game is played in: a fixed biome with its resistances, no twist,
    * but enemies still draw their fork choices from the seed, just as a real run does.
    * A rolled setup plays the run the game would roll from the seed instead.
    */
  def scenarioRun(seed: Long, biome: String, setup: Setup = Setup()): RunContext =
    val run =
      if setup.rolled then RunGen.roll(seed, setup.talents)
      else
        val lanes = Vector.iterate((Rng(seed), Vector.empty[Long]), 512) { case (rng, acc) =>
          val (v, next) = rng.nextLong()
          (next, acc :+ (v & Long.MaxValue))
        }.last._2
        val b = Config.biomes.find(_.id == biome)
        RunContext(seed = seed, biome = biome, laneChoices = lanes, talents = setup.talents,
          resistTheme = b.fold(RunContext(seed).resistTheme)(_.resistTheme), roster = b.fold(Vector.empty)(_.roster))
    run.copy(loadout = setup.loadout, relics = setup.relics, augments = setup.augment.toVector)

  /** The world a replay starts from. The config must already be the recorded one. */
  def initialWorld(r: Replay): World =
    if r.kind == "campaign" then Sim.initial(r.seed)
    else scenarioWorld(r.seed, r.biome, r.level, r.setup)

  /** A single-level scenario's first state: a fresh run started at `level` with
    * that level's start gold.
    */
  def scenarioWorld(seed: Long, biome: String, level: Int, setup: Setup = Setup()): World =
    val w = Sim.initial(scenarioRun(seed, biome, setup))
    w.copy(level = level, gold = Sim.startGold(w.run, level), pendingAugment = setup.augment,
      lives = math.max(1, w.lives - setup.livesLost))

  /** The end state as one line, identical for identical games. */
  def resultOf(w: World, steps: Int, startLevel: Option[Int] = None): String =
    val state =
      if w.isOver then "LOST"
      else if w.won then "WON"
      // a single-level game is over once its level is cleared: at the draft, or past
      // it when there was nothing to draft and the next level already began
      else if w.draft.isDefined || startLevel.exists(w.level > _) then "CLEARED"
      else "RUNNING"
    s"$state lives=${w.lives} gold=${w.gold} kills=${w.kills} leaks=${w.leaks} wave=${w.wave} level=${w.level} steps=$steps"

  /** Plays the whole recording back headlessly and returns the end state line. The
    * config must already be the recorded one.
    */
  def verify(r: Replay): String =
    val byStep = r.commands.toMap
    val end = (0 until r.steps).foldLeft(initialWorld(r)) { (w, i) =>
      val next = Sim.step(w, Config.MaxStepDt, byStep.getOrElse(i, Vector.empty))
      next.copy(events = Vector.empty, hitFeedback = Vector.empty, nameEvents = Vector.empty)
    }
    resultOf(end, r.steps, Option.when(r.kind == "scenario")(r.level))

  // -- json -------------------------------------------------------------------

  def toJson(r: Replay): String =
    val root = obj(
      "kind"    -> JsonValue(r.kind),
      "bot"     -> JsonValue(r.bot),
      "seed"    -> JsonValue(r.seed),
      "biome"   -> JsonValue(r.biome),
      "level"   -> JsonValue(r.level.toLong),
      "think"   -> JsonValue(r.think.toLong),
      "config"  -> JsonValue(r.config),
      "result"  -> JsonValue(r.result),
      "steps"   -> JsonValue(r.steps.toLong),
      "loadout" -> arr(r.setup.loadout.map(JsonValue(_))),
      "talents" -> arr(r.setup.talents.map(JsonValue(_))),
      "relics"  -> arr(r.setup.relics.map(JsonValue(_))),
      "augment" -> r.setup.augment.fold(JsonValue(JsonValue.ValueType.nullValue))(JsonValue(_)),
      "livesLost" -> JsonValue(r.setup.livesLost.toLong),
      "rolled"  -> JsonValue(r.setup.rolled),
      "recorded" -> JsonValue(r.recordedAt),
      "commands" -> arr(r.commands.map { case (step, cmds) =>
        obj("step" -> JsonValue(step.toLong), "do" -> arr(cmds.map(encode)))
      })
    )
    root.prettyPrint(JsonWriter.OutputType.json, 2)

  def parse(text: String): Either[String, Replay] =
    Try {
      val root = JsonReader().parse(text)
      val cmds = root.get("commands")
      val kind  = root.getString("kind")
      val level = root.getInt("level")
      // Older single-level recordings called a level cleared past its (empty) draft RUNNING.
      val result = root.getString("result") match
        case r if kind == "scenario" && r.startsWith("RUNNING ") &&
            r.split(' ').collectFirst { case kv if kv.startsWith("level=") => kv.drop(6).toInt }.exists(_ > level) =>
          "CLEARED" + r.drop("RUNNING".length)
        case r => r
      Replay(
        kind = kind,
        bot = root.getString("bot"),
        seed = root.getLong("seed"),
        biome = root.getString("biome"),
        level = level,
        think = root.getInt("think"),
        config = root.getString("config"),
        result = result,
        recordedAt = root.getLong("recorded", 0L),
        steps = root.getInt("steps"),
        setup = {
          def strings(name: String): Vector[String] =
            val l = root.get(name)
            if l == null then Vector.empty else (0 until l.size).map(i => l.get(i).asString()).toVector
          val aug = root.get("augment")
          Setup(strings("loadout"), strings("talents"), strings("relics"),
            if aug == null || aug.isNull then None else Some(aug.asString()), root.getInt("livesLost", 0),
            root.getBoolean("rolled", false))
        },
        commands = (0 until cmds.size).map { i =>
          val entry = cmds.get(i)
          val ops   = entry.get("do")
          entry.getInt("step") -> (0 until ops.size).map(j => decode(ops.get(j))).toVector
        }.toVector
      )
    }.toEither.left.map(e => s"bad replay: ${e.getMessage}")

  private def encode(c: Command): JsonValue = c match
    case Command.Build(cell, tpe) =>
      obj("op" -> JsonValue("build"), "x" -> JsonValue(cell.x.toLong), "y" -> JsonValue(cell.y.toLong), "tower" -> JsonValue(tpe))
    case Command.AttachAugment(cell) =>
      obj("op" -> JsonValue("attach"), "x" -> JsonValue(cell.x.toLong), "y" -> JsonValue(cell.y.toLong))
    case Command.PickRelic(id)   => obj("op" -> JsonValue("relic"), "id" -> JsonValue(id))
    case Command.PickAugment(id) => obj("op" -> JsonValue("augment"), "id" -> JsonValue(id))
    case Command.Reroll()        => obj("op" -> JsonValue("reroll"))
    case Command.GrantGold(n)    => obj("op" -> JsonValue("gold"), "amount" -> JsonValue(n.toLong))
    case Command.UnlockTowers()  => obj("op" -> JsonValue("unlock"))

  private def decode(v: JsonValue): Command =
    v.getString("op") match
      case "build"   => Command.Build(GridPos(v.getInt("x"), v.getInt("y")), v.getString("tower"))
      case "attach"  => Command.AttachAugment(GridPos(v.getInt("x"), v.getInt("y")))
      case "relic"   => Command.PickRelic(v.getString("id"))
      case "augment" => Command.PickAugment(v.getString("id"))
      case "reroll"  => Command.Reroll()
      case "gold"    => Command.GrantGold(v.getInt("amount"))
      case "unlock"  => Command.UnlockTowers()
      case other     => throw IllegalArgumentException(s"unknown command op '$other'")

  private def obj(fields: (String, JsonValue)*): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    fields.foreach((k, v) => o.addChild(k, v))
    o

  private def arr(items: Seq[JsonValue]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    items.foreach(a.addChild(_))
    a

/** Files under a replay directory: `configs/<hash>.json` holds each config
  * snapshot once; `<name>.json` is one recorded game.
  */
object ReplayStore:
  def DefaultDir: String = DataDir.path("replays")

  /** Writes the config snapshot (once per distinct config) and returns its hash. */
  def saveConfig(dir: String, cfg: GameConfig): String =
    val text = ConfigWriter.toJson(cfg)
    val hash = sha256(text).take(12)
    val path = Paths.get(dir, "configs", s"$hash.json")
    if !Files.isRegularFile(path) then
      Files.createDirectories(path.getParent)
      Files.writeString(path, text + "\n", StandardCharsets.UTF_8)
    hash

  def loadConfig(dir: String, hash: String): Either[String, GameConfig] =
    val path = Paths.get(dir, "configs", s"$hash.json")
    if !Files.isRegularFile(path) then Left(s"missing config snapshot $hash")
    else ConfigLoader.parse(Files.readString(path, StandardCharsets.UTF_8))

  def write(dir: String, name: String, r: Replay): String =
    val path = Paths.get(dir, s"$name.json")
    Files.createDirectories(path.getParent)
    Files.writeString(path, Replay.toJson(r) + "\n", StandardCharsets.UTF_8)
    path.toAbsolutePath.normalize.toString

  /** Every recorded game in the directory, newest first. Unreadable files are skipped;
    * recordings without a timestamp get their file's modification time.
    */
  def list(dir: String): Vector[(String, Replay)] =
    val d = Paths.get(dir)
    if !Files.isDirectory(d) then Vector.empty
    else
      val files = Files.list(d).toArray.toVector.map(_.asInstanceOf[Path])
        .filter(p => p.toString.endsWith(".json") && Files.isRegularFile(p))
        .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
      files.flatMap { p =>
        val text = Files.readString(p, StandardCharsets.UTF_8)
        Replay.parse(text).toOption.map { r =>
          val at = if r.recordedAt > 0 then r.recordedAt else Files.getLastModifiedTime(p).toMillis
          p.toAbsolutePath.normalize.toString -> r.copy(recordedAt = at)
        }
      }

  private def sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x").mkString

/** Step-by-step playback of a recording, for the GUI. [[advance]] runs as many
  * simulation steps as real time allows at `speed` (1.0 is real time), applying
  * the recorded commands on the same steps as the original game.
  */
final case class Playback(
    replay: Replay,
    world: World,
    index: Int,
    speed: Double,
    paused: Boolean,
    carry: Double,
    byStep: Map[Int, Vector[Command]]
):
  def finished: Boolean = index >= replay.steps

  def advance(realDt: Float): (Playback, Vector[HitFeedback], Vector[NameEvent]) =
    if paused || finished then (copy(carry = 0.0), Vector.empty, Vector.empty)
    else
      val due = carry + speed * realDt / Config.MaxStepDt
      val n   = math.min(Playback.MaxStepsPerFrame, due.toInt)
      var w    = world
      var i    = index
      val hits = Vector.newBuilder[HitFeedback]
      val names = Vector.newBuilder[NameEvent]
      var k    = 0
      while k < n && i < replay.steps do
        val next = Sim.step(w, Config.MaxStepDt, byStep.getOrElse(i, Vector.empty))
        hits ++= next.hitFeedback
        names ++= next.nameEvents
        w = next.copy(events = Vector.empty, hitFeedback = Vector.empty, nameEvents = Vector.empty)
        i += 1
        k += 1
      (copy(world = w, index = i, carry = due - n), hits.result(), names.result())

  /** Fraction of the recording played, 0..1. */
  def progress: Float = if replay.steps == 0 then 1f else index.toFloat / replay.steps

  /** Jumps to step `target`: forward by stepping on, backward by replaying from the
    * start (the simulation is deterministic, so the result is the same game state).
    */
  def seek(target: Int): Playback =
    val to    = math.max(0, math.min(replay.steps, target))
    val start = if to < index then copy(world = Replay.initialWorld(replay), index = 0) else this
    var w = start.world
    var i = start.index
    while i < to do
      w = Sim.step(w, Config.MaxStepDt, byStep.getOrElse(i, Vector.empty))
        .copy(events = Vector.empty, hitFeedback = Vector.empty, nameEvents = Vector.empty)
      i += 1
    start.copy(world = w, index = i, carry = 0.0)

  /** The next speed up (dir = 1) or down (dir = -1) from [[Playback.Speeds]]. */
  def faster(dir: Int): Playback =
    val i = Playback.Speeds.indexWhere(_ >= speed) match
      case -1 => Playback.Speeds.size - 1
      case k  => k
    copy(speed = Playback.Speeds(math.max(0, math.min(Playback.Speeds.size - 1, i + dir))))

object Playback:
  val MaxStepsPerFrame: Int = 50
  val Speeds: Vector[Double] = Vector(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)

  def start(r: Replay): Playback =
    Playback(
      replay = r,
      world = Replay.initialWorld(r),
      index = 0,
      speed = 1.0,
      paused = false,
      carry = 0.0,
      byStep = r.commands.toMap
    )
