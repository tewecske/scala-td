TASK 13 — PLAN (Config Editor App)
===================================

Research
--------
Verified against the current codebase:

- **`editor` subproject** (`build.sbt:32-35`) already `.dependsOn(core)` and has
  `name := "editor"` but NO `mainClass` and NO `fork`. Its only source file is a
  stub `editor/src/main/scala/td/EditorMain.scala` (`object EditorMain`, no
  `main`), so `sbt "editor/run"` currently fails with "no main class".
- **`ConfigLoader`** (`core/src/main/scala/td/ConfigLoader.scala`) is the single
  source of truth for the format and is headless-safe:
  - `FileName = "config.json"` (line 15); `load()` (line 25) reads cwd file if
    present else the bundled classpath resource; `load(path)` (line 33) reads an
    explicit path; `parse(text): Either[String, GameConfig]` (line 63) does ALL
    validation (unique ids, enum resolve, wave count == composition total, biome
    roster/`tutorial`/resist-theme rules, talent prereqs, relic gating, route
    branch ranges, etc.). A bad config yields a `Left(msg)`.
  - `parseColor` (line 49) is the public `#RRGGBB`/`#RRGGBBAA` -> `Color` helper
    the editor can reuse verbatim for color-field validation.
- **Scaling gotcha**: the loader multiplies three JSON fields by `board.cellPx`
  when parsing, storing them in WORLD units — `TowerSpec.range` (line 149) and
  `TowerSpec.splashRadius` (line 153) (see also the comment at
  `GameConfig.scala:5-7`), and `Aura.radius` (line 165). A writer MUST divide
  those three by `cellPx` to reproduce the JSON values the author typed.
- **No writer exists.** `ConfigLoader` only reads (`JsonReader` is read-only).
  There is no `GameConfig -> JSON` serialization anywhere, so the editor cannot
  yet write back.
- **libGDX `JsonValue` can build + emit JSON** (same library the loader parses
  with, so "same shape" is guaranteed by construction). Verified via `javap` on
  `gdx-1.14.2.jar`:
  - Public constructors: `JsonValue(String)`, `JsonValue(double)`, `JsonValue(long)`,
    `JsonValue(boolean)`, `JsonValue(ValueType)`.
  - `addChild(String, JsonValue)` (named, for objects) and `addChild(JsonValue)`
    (unnamed, for arrays) build the tree; `toJson(JsonWriter.OutputType)` and
    `prettyPrint(JsonWriter.OutputType, int)` emit it.
  - `JsonValue.ValueType` constants are `object, array, stringValue, doubleValue,
    longValue, booleanValue, nullValue`. **`object` is a Scala keyword** and must
    be referenced with backticks (`JsonValue.ValueType.`object``).
  - Number emission is `Double.toString(doubleValue)` / `Long.toString(longValue)`.
    Passing a raw `Float` widens to `double` and prints
    `0.05000000074505806` — ugly. Passing `Float.toString(f).toDouble` prints
    `0.05` and still round-trips exactly (verified for every float in the
    bundled config: `(float) Double.parseDouble(Float.toString(f)) == f`).
  - Loader `asFloat()` on a `doubleValue` node is a `d2f` cast
    (`(float) doubleValue`), so `JsonValue(f.toDouble)` is always exact; the
    `Float.toString(f).toDouble` form is exact for all realistic tuning
    magnitudes and is guarded by the round-trip test (see Gotchas).
- **`Color.equals` compares 8-bit quantized bits** (`toIntBits`), so
  `parseColor(hex)` -> `Color` -> `colorHex` -> `parseColor` is bit-exact as
  long as `colorHex` uses `Math.round` (not `(int)` truncation) on `v * 255f`.
- **Model types the editor must build** (`GameConfig.scala`, `Model.scala`):
  `BoardConfig`, `EconomyConfig`, `MetaConfig`, `DraftConfig`, `CombatConfig`,
  `I18nConfig`, `Colors` (22 `Color` fields), `TowerSpec`, `EnemySpec`
  (`Resistances` x5, `Option[Aura]`, `heavy: Boolean`, `Option[RouteSide]`),
  `LevelConfig` (`Route` = `waypoints: Vector[GridPos]` +
  `branches: Vector[Branch]`, `waves: Vector[WaveSpec]` with
  `composition: Vector[(String, Int)]`), `Biome`, `Twist`, `Relic`, `Augment`,
  `Talent`, `Effect(kind, params: Map[String, Float])`.
- **Enums** with `.values` (used for dropdowns and `enumValue`-style lookups):
  `DamageKind` (`Model.scala:50`), `AuraKind` (`Model.scala:40`), `RouteSide`
  (`GameConfig.scala:24`), `Rarity`, `TalentLayer` (`GameConfig.scala`).
- **Critical coupling gotcha**: `object Config` initializes
  `current = ConfigLoader.initial()` on first access and THROWS if the cwd
  `config.json` is unparseable (`Config.scala:10`); `Messages` reads
  `Config.game.i18n.lang` at init (`Messages.scala:18`). So an editor that
  touches `Config.game` or `Messages` cannot open a broken config — which defeats
  the tool's purpose. The editor must operate ONLY through `ConfigLoader` +
  `ConfigWriter` + the pure model case classes/enums, never `Config.game`.
- `messages_en.properties` currently has no editor keys; all chrome is game-only.
- `GridPos(x: Int, y: Int)` (`Grid.scala:3`) is pure (its `.centerX/.centerY`
  touch `Config.CellPx`, but the editor only builds `GridPos` and reads `.x/.y`).

Decisions (confirmed)
---------------------
1. **Editor never touches `Config.game` or `Messages`.** It works purely on
   `ConfigLoader` (parse/load/`parseColor`/`FileName`), `ConfigWriter`, `ConfigCodec`,
   and the pure model classes + enum `.values`. Consequence: editor chrome
   (window title, Save/Reload, error dialogs) uses plain string literals — this
   is a developer tool whose labels are the JSON schema, not localized game
   content (see Open questions #1).
2. **Validation = re-parse.** The editor gathers edits into a `GameConfig`,
   serializes with `ConfigWriter.toJson`, then `ConfigLoader.parse(...)`. `Right`
   => write; `Left(err)` => reject with the message. This reuses EVERY loader
   rule (unique ids, enum resolve, wave-count consistency, cross-refs) with zero
   duplicated logic, exactly as the task demands.
3. **Serialization is a new core object `ConfigWriter`**, building a libGDX
   `JsonValue` tree (the loader's own representation) and `prettyPrint`-ing it.
   `toJson(cfg): String` + `write(cfg, path): Either[String, Unit]`.
4. **`colorHex` is public** on `ConfigWriter` (the editor's color field needs
   `Color -> hex`); it inverts `ConfigLoader.parseColor` (6 hex digits when
   alpha==1, else 8).
5. **Array-typed fields are edited as text** through a new core `ConfigCodec`
   (headlessly testable), with `format`/`parse` pairs returning
   `Either[String, A]`: compositions (`Grunt:6,Swift:2`), waypoints
   (`0,17 17,17`), name lists (`Grunt,Swift,Ember`), and branches
   (`side:Right from:2 to:5 waypoints:8,3`). This maps directly to the task's
   "arrays (compositions/waypoints)" field kind.
6. **No add/remove of list items in v1.** Towers/enemies/levels/waves/biomes/
   talents/etc. are rendered and edited at their existing count; only their
   VALUES are editable (see Open questions #2).
7. **Editor writes to `ConfigLoader.FileName`** (the cwd `config.json`, the file
   the game actually prefers over the bundled resource). A "Reload" button
   re-reads it; startup falls back to the bundled resource (new
   `ConfigLoader.bundled()`) with a warning if the cwd file is broken, so the
   editor can still open to fix it.
8. **No `fork := true` on `editor`.** Swing runs fine in the sbt JVM; use
   `DISPOSE_ON_CLOSE` (not `EXIT_ON_CLOSE`, which would `System.exit` the whole
   sbt JVM). Only add `Compile / mainClass := Some("td.ConfigEditorApp")`.
9. **Ranges are editor-side field metadata**, not loader rules (the loader does
   not range-check; only the re-parse is authoritative). They live at the field
   call sites as `IntField`/`FloatField` constructor args.
10. **Effect params keep `Map[String, Float]`.** Serialized param ORDER is not
    guaranteed past 4 entries (immutable HashMap), which is cosmetic only —
    parse equality is order-independent and the game loads regardless (see Open
    questions #3).

Steps
-----

### Step 1 — `core`: `ConfigWriter.scala` (new)

The serializer. Reverse of `parseRoot`, incl. the three `cellPx` divisions and
`Color -> hex`.

```scala
package td

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.{JsonValue, JsonWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try

/** Serializes a [[GameConfig]] back to the JSON shape [[ConfigLoader]] parses,
  * so the editor writes exactly what the game loads. Built as a libGDX
  * `JsonValue` tree (the loader's own representation) and pretty-printed.
  */
object ConfigWriter:

  def toJson(cfg: GameConfig): String =
    root(cfg).prettyPrint(JsonWriter.OutputType.json, 2)

  def write(cfg: GameConfig, path: String): Either[String, Unit] =
    Try(Files.writeString(Paths.get(path), toJson(cfg) + "\n", StandardCharsets.UTF_8))
      .toEither.left.map(_.getMessage)

  /** `Color` -> `#RRGGBB` (or `#RRGGBBAA` when alpha != 1). Inverse of
    * [[ConfigLoader.parseColor]]. Round-trips exactly: channels are 0..255.
    */
  def colorHex(c: Color): String =
    def ch(v: Float): Int = math.round(v * 255f).max(0).min(255)
    val (r, g, b, a) = (ch(c.r), ch(c.g), ch(c.b), ch(c.a))
    if a == 255 then f"#$r%02X$g%02X$b%02X" else f"#$r%02X$g%02X$b%02X$a%02X"

  // -- tree builders -----------------------------------------------------------

  private def obj(fields: (String, JsonValue)*): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    fields.foreach { (k, v) => o.addChild(k, v) }
    o

  private def arr(items: Seq[JsonValue]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    items.foreach(a.addChild(_))
    a

  private def str(s: String): JsonValue        = JsonValue(s)
  private def bool(b: Boolean): JsonValue      = JsonValue(b)
  private def num(i: Int): JsonValue           = JsonValue(i.toLong)
  private def num(f: Float): JsonValue         = JsonValue(java.lang.Float.toString(f).toDouble)
  private def nullV: JsonValue                 = JsonValue(JsonValue.ValueType.nullValue)
```

Then `root` and one builder per section. The three `cellPx` reversals live in
`tower` and `enemy`; optional fields emit `null` (fresh node each call):

```scala
  private def root(cfg: GameConfig): JsonValue =
    obj(
      "board"    -> board(cfg.board),
      "economy"  -> economy(cfg.economy),
      "meta"     -> meta(cfg.meta),
      "draft"    -> draft(cfg.draft),
      "combat"   -> combat(cfg.combat),
      "i18n"     -> i18n(cfg.i18n),
      "towers"   -> arr(cfg.towers.map(tower(cfg, _))),
      "enemies"  -> arr(cfg.enemies.map(enemy(cfg, _))),
      "levels"   -> arr(cfg.levels.map(level(cfg, _))),
      "colors"   -> colors(cfg.colors),
      "biomes"   -> arr(cfg.biomes.map(biome)),
      "twists"   -> arr(cfg.twists.map(twist)),
      "relics"   -> arr(cfg.relics.map(relic)),
      "augments" -> arr(cfg.augments.map(augment)),
      "talents"  -> arr(cfg.talents.map(talent))
    )

  private def board(b: BoardConfig): JsonValue =
    obj("gridSize" -> num(b.gridSize), "cellPx" -> num(b.cellPx), "panelPx" -> num(b.panelPx))

  private def economy(e: EconomyConfig): JsonValue =
    obj("startGold" -> num(e.startGold), "startLives" -> num(e.startLives),
        "killReward" -> num(e.killReward), "levelBonus" -> num(e.levelBonus))

  // meta / draft / combat / i18n are the same shape (obj of scalars).

  private def tower(cfg: GameConfig, t: TowerSpec): JsonValue =
    val px = cfg.board.cellPx
    obj(
      "name"            -> str(t.name),
      "cost"            -> num(t.cost),
      "color"           -> str(colorHex(t.color)),
      "range"           -> num(t.range / px),
      "fireInterval"    -> num(t.fireInterval),
      "damage"          -> num(t.damage),
      "projectileSpeed" -> num(t.projectileSpeed),
      "kind"            -> str(t.kind.toString),
      "splashRadius"    -> num(t.splashRadius / px)
    )

  private def enemy(cfg: GameConfig, e: EnemySpec): JsonValue =
    val px = cfg.board.cellPx
    val aura = e.aura.map(a => obj(
      "kind" -> str(a.kind.toString), "radius" -> num(a.radius / px), "magnitude" -> num(a.magnitude)
    )).getOrElse(nullV)
    val side = e.sidePreference.map(s => str(s.toString)).getOrElse(nullV)
    obj(
      "name"     -> str(e.name),
      "baseHp"   -> num(e.baseHp),
      "baseSpeed"-> num(e.baseSpeed),
      "color"    -> str(colorHex(e.color)),
      "size"     -> num(e.size),
      "resist"   -> obj(
        "kinetic" -> num(e.resist.kinetic), "fire" -> num(e.resist.fire),
        "slow" -> num(e.resist.slow), "splash" -> num(e.resist.splash),
        "explosion" -> num(e.resist.explosion)
      ),
      "aura"     -> aura,
      "heavy"    -> bool(e.heavy),
      "sidePreference" -> side
    )

  private def level(cfg: GameConfig, l: LevelConfig): JsonValue =
    obj("name" -> str(l.name), "route" -> route(l.route),
        "waves" -> arr(l.waves.map(wave)))

  private def route(r: Route): JsonValue =
    obj("waypoints" -> arr(r.waypoints.map(pos)), "branches" -> arr(r.branches.map(branch)))

  private def pos(p: GridPos): JsonValue = arr(Seq(num(p.x), num(p.y)))

  private def branch(b: Branch): JsonValue =
    obj("side" -> str(b.side.toString), "from" -> num(b.from), "to" -> num(b.to),
        "waypoints" -> arr(b.waypoints.map(pos)))

  private def wave(w: WaveSpec): JsonValue =
    obj("count" -> num(w.count), "spawnInterval" -> num(w.spawnInterval),
        "hpScale" -> num(w.hpScale), "composition" -> composition(w.composition))

  private def composition(c: Vector[(String, Int)]): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    c.foreach { (name, n) => o.addChild(name, num(n)) }
    o

  private def colors(c: Colors): JsonValue =
    obj(
      "background" -> str(colorHex(c.background)), "path" -> str(colorHex(c.path)),
      "build" -> str(colorHex(c.build)), "gridLine" -> str(colorHex(c.gridLine)),
      "enemy" -> str(colorHex(c.enemy)), "shot" -> str(colorHex(c.shot)),
      "shotFire" -> str(colorHex(c.shotFire)), "shotFrost" -> str(colorHex(c.shotFrost)),
      "shotSplash" -> str(colorHex(c.shotSplash)), "shotBoom" -> str(colorHex(c.shotBoom)),
      "burnTint" -> str(colorHex(c.burnTint)), "slowTint" -> str(colorHex(c.slowTint)),
      "buffTint" -> str(colorHex(c.buffTint)), "panel" -> str(colorHex(c.panel)),
      "hoverOk" -> str(colorHex(c.hoverOk)), "hoverBad" -> str(colorHex(c.hoverBad)),
      "range" -> str(colorHex(c.range)), "buttonFill" -> str(colorHex(c.buttonFill)),
      "hpBarBack" -> str(colorHex(c.hpBarBack)), "hpBarFill" -> str(colorHex(c.hpBarFill)),
      "text" -> str(colorHex(c.text)), "dimText" -> str(colorHex(c.dimText))
    )

  private def effect(e: Effect): JsonValue =
    obj("kind" -> str(e.kind), "params" -> params(e.params))

  private def params(m: Map[String, Float]): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    m.foreach { (k, v) => o.addChild(k, num(v)) }
    o

  private def biome(b: Biome): JsonValue =
    val env = b.environment.map(effect).getOrElse(nullV)
    obj("id" -> str(b.id), "name" -> str(b.name), "roster" -> arr(b.roster.map(str)),
        "environment" -> env,
        "resistTheme" -> obj(
          "primary" -> str(b.resistTheme.primary.toString),
          "primaryAmount" -> num(b.resistTheme.primaryAmount),
          "secondary" -> str(b.resistTheme.secondary.toString),
          "secondaryAmount" -> num(b.resistTheme.secondaryAmount)))

  private def twist(t: Twist): JsonValue =
    obj("id" -> str(t.id), "name" -> str(t.name),
        "targetTower" -> str(t.targetTower), "effect" -> effect(t.effect))

  private def relic(r: Relic): JsonValue =
    val req = r.requiredTalent.map(str).getOrElse(nullV)
    obj("id" -> str(r.id), "name" -> str(r.name), "rarity" -> str(r.rarity.toString),
        "requiredTalent" -> req, "effect" -> effect(r.effect))

  private def augment(a: Augment): JsonValue =
    obj("id" -> str(a.id), "name" -> str(a.name), "effect" -> effect(a.effect))

  private def talent(t: Talent): JsonValue =
    obj("id" -> str(t.id), "name" -> str(t.name), "layer" -> str(t.layer.toString),
        "cost" -> num(t.cost), "prereqs" -> arr(t.prereqs.map(str)),
        "effect" -> effect(t.effect))
```

The `meta`, `draft`, `combat`, `i18n` builders are omitted for brevity but are
identical `obj(...)` shapes (combat = 9 scalars incl. two `Int`s; i18n =
`lang` string + `showCodes` bool).

### Step 2 — `core`: `ConfigCodec.scala` (new)

Editor-facing text codecs for the array-valued fields, headlessly testable. Each
`parse` returns `Either[String, A]`; semantic rules (non-empty route, wave-count
consistency, unknown names) are left to the re-parse, not duplicated here.

```scala
package td

/** Text codecs for the array-valued config fields, so the Swing editor edits
  * them as plain strings while validation stays in the loader (via re-parse).
  * Syntax is editor-facing; the on-disk JSON shape is untouched (ConfigWriter).
  */
object ConfigCodec:

  def formatComposition(c: Vector[(String, Int)]): String =
    c.map((n, k) => s"$n:$k").mkString(",")

  def parseComposition(s: String): Either[String, Vector[(String, Int)]] =
    if s.trim.isEmpty then Right(Vector.empty)
    else
      s.split(",").toVector.map(_.trim).foldLeft[Either[String, Vector[(String, Int)]]](Right(Vector.empty)) {
        (acc, tok) =>
          for
            xs <- acc
            pair <- tok.split(":", 2) match
              case Array(n, k) =>
                k.trim.toIntOption match
                  case Some(i) if i >= 0 => Right(n.trim -> i)
                  case _                => Left(s"bad composition entry '$tok' (want name:count)")
              case _ => Left(s"bad composition entry '$tok' (want name:count)")
          yield xs :+ pair
      }

  def formatWaypoints(ws: Vector[GridPos]): String =
    ws.map(p => s"${p.x},${p.y}").mkString(" ")

  def parseWaypoints(s: String): Either[String, Vector[GridPos]] =
    if s.trim.isEmpty then Right(Vector.empty)
    else
      s.trim.split("\\s+").toVector.foldLeft[Either[String, Vector[GridPos]]](Right(Vector.empty)) {
        (acc, tok) =>
          for
            xs <- acc
            p <- tok.split(",", 2) match
              case Array(x, y) => (x.trim.toIntOption, y.trim.toIntOption) match
                case (Some(px), Some(py)) => Right(GridPos(px, py))
                case _                    => Left(s"bad waypoint '$tok' (want x,y)")
              case _ => Left(s"bad waypoint '$tok' (want x,y)")
          yield xs :+ p
      }

  def formatNames(ns: Vector[String]): String = ns.mkString(",")

  def parseNames(s: String): Vector[String] =
    s.split(",").toVector.map(_.trim).filter(_.nonEmpty)

  def formatBranches(bs: Vector[Branch]): String =
    bs.map(b => s"side:${b.side} from:${b.from} to:${b.to} waypoints:${formatWaypoints(b.waypoints)}")
      .mkString("; ")

  def parseBranches(s: String): Either[String, Vector[Branch]] =
    if s.trim.isEmpty then Right(Vector.empty)
    else
      s.split(";").toVector.map(_.trim).foldLeft[Either[String, Vector[Branch]]](Right(Vector.empty)) {
        (acc, tok) =>
          for
            xs <- acc
            b <- parseBranch(tok)
          yield xs :+ b
      }

  private def parseBranch(tok: String): Either[String, Branch] =
    val parts = tok.split("\\s+").toVector.map(_.trim)
    val side  = parts.find(_.startsWith("side:")).map(_.stripPrefix("side:"))
    val from  = parts.find(_.startsWith("from:")).flatMap(_.stripPrefix("from:").toIntOption)
    val to    = parts.find(_.startsWith("to:")).flatMap(_.stripPrefix("to:").toIntOption)
    val wps   = parts.find(_.startsWith("waypoints:")).map(_.stripPrefix("waypoints:")).getOrElse("")
    for
      s <- side.flatMap(n => RouteSide.values.find(_.toString == n)).toRight(s"bad branch '$tok' (side)")
      fr <- from.toRight(s"bad branch '$tok' (from)")
      to <- to.toRight(s"bad branch '$tok' (to)")
      ws <- parseWaypoints(wps)
    yield Branch(s, fr, to, ws)
```

### Step 3 — `core`: `ConfigLoader.bundled()` helper (new, small)

So the editor can open with the bundled config when the cwd file is broken:

```scala
  /** The bundled classpath config, ignoring any working-dir override. */
  def bundled(): Either[String, GameConfig] =
    val in = getClass.getResourceAsStream("/" + FileName)
    if in == null then Left(s"bundled $FileName missing from classpath")
    else parse(Source.fromInputStream(in).mkString)
```

(`Source` is already imported in `ConfigLoader`.)

### Step 4 — `core` tests: `ConfigWriterTest.scala` + `ConfigCodecTest.scala` (new)

```scala
package td

class ConfigWriterTest extends munit.FunSuite:
  private val cfg = Config.game

  test("bundled config round-trips through toJson -> parse") {
    assertEquals(ConfigLoader.parse(ConfigWriter.toJson(cfg)), Right(cfg))
  }

  test("cell-scaled range/splash/aura radius are divided back to cell units") {
    val json = ConfigWriter.toJson(cfg)
    assert(json.contains("\"range\": 3.2"))          // tower Basic range (cells)
    assert(json.contains("\"splashRadius\": 1.2"))   // Cannon (cells)
  }

  test("colorHex round-trips alpha and opaque colors") {
    assertEquals(ConfigWriter.colorHex(ConfigLoader.parseColor("#5C63A5")), "#5C63A5")
    assertEquals(ConfigWriter.colorHex(ConfigLoader.parseColor("#64C15F99")), "#64C15F99")
  }

  test("write produces a file the loader reads back") {
    val f = java.io.File.createTempFile("cfg", ".json")
    f.deleteOnExit()
    ConfigWriter.write(cfg, f.getAbsolutePath)
    assertEquals(ConfigLoader.load(f.getAbsolutePath), Right(cfg))
  }
```

```scala
package td

class ConfigCodecTest extends munit.FunSuite:
  test("composition format/parse round-trip") {
    val c = Vector("Grunt" -> 6, "Swift" -> 2)
    assertEquals(ConfigCodec.parseComposition(ConfigCodec.formatComposition(c)), Right(c))
  }

  test("composition rejects non-integer counts and malformed entries") {
    assert(ConfigCodec.parseComposition("Grunt:x").isLeft)
    assert(ConfigCodec.parseComposition("Grunt:-1").isLeft)
    assert(ConfigCodec.parseComposition("nonsense").isLeft)
  }

  test("waypoints format/parse round-trip and reject bad tokens") {
    val w = Vector(GridPos(0, 17), GridPos(17, 17))
    assertEquals(ConfigCodec.parseWaypoints(ConfigCodec.formatWaypoints(w)), Right(w))
    assert(ConfigCodec.parseWaypoints("1,2 3").isLeft)
  }

  test("names and branches round-trip") {
    assertEquals(ConfigCodec.parseNames("Grunt, Swift ,Ember"), Vector("Grunt", "Swift", "Ember"))
    val b = Vector(Branch(RouteSide.Right, 2, 5, Vector(GridPos(8, 3))))
    assertEquals(ConfigCodec.parseBranches(ConfigCodec.formatBranches(b)), Right(b))
  }
```

Note: `ConfigWriterTest` touches `Config.game` (fine — tests run with the bundled
config; no broken cwd file under test). The round-trip test is the guard for
decision on `Float.toString(f).toDouble` float formatting.

### Step 5 — `editor`: `ConfigEditorApp.scala` (new)

Replace the `EditorMain` stub usage (leave `EditorMain.scala` in place or delete
it; the new `mainClass` points at `ConfigEditorApp`). Structure:

- `object ConfigEditorApp { def main(args) }` — `SwingUtilities.invokeLater`
  to show a `ConfigEditorFrame`.
- `final class ConfigEditorFrame extends JFrame` — a `JTabbedPane` (one tab per
  top-level section), a bottom bar (Reload / Save buttons + status label), and
  `setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)`.
- Field widgets (see below) + per-section builders that lay fields out in a
  `GridLayout(0, 2)` (label, widget) and, on Save, read them back into the model
  (`Either[String, GameConfig]`).

Field widgets (typed, each with `label`, `component: JComponent`, `read`):

```scala
import java.awt.GridLayout
import javax.swing.*
import com.badlogic.gdx.graphics.Color
import scala.util.Try

sealed trait Field:
  def label: String
  def component: JComponent

final class IntField(val label: String, min: Int, max: Int, initial: Int) extends Field:
  private val spinner = JSpinner(SpinnerNumberModel(initial, min, max, 1))
  val component: JComponent = spinner
  def read: Either[String, Int] = Right(spinner.getValue.asInstanceOf[Integer].intValue)

final class FloatField(val label: String, min: Float, max: Float, initial: Float) extends Field:
  private val text = JTextField(initial.toString, 12)
  val component: JComponent = text
  def read: Either[String, Float] =
    text.getText.trim.toFloatOption match
      case None                  => Left(s"'$label' is not a number")
      case Some(v) if v < min || v > max => Left(s"'$label' must be in [$min,$max]")
      case Some(v)               => Right(v)

final class StringField(val label: String, initial: String) extends Field:
  private val text = JTextField(initial, 16)
  val component: JComponent = text
  def read: Either[String, String] = Right(text.getText)

final class BoolField(val label: String, initial: Boolean) extends Field:
  private val box = JCheckBox()
  box.setSelected(initial)
  val component: JComponent = box
  def read: Either[String, Boolean] = Right(box.isSelected)

final class EnumField(val label: String, values: Vector[String], initial: String) extends Field:
  private val combo = JComboBox(values.toArray)
  combo.setSelectedItem(initial)
  val component: JComponent = combo
  def read: Either[String, String] = Right(combo.getSelectedItem.asInstanceOf[String])

final class ColorField(val label: String, initial: Color) extends Field:
  private val text = JTextField(ConfigWriter.colorHex(initial), 9)
  val component: JComponent = text
  def read: Either[String, Color] =
    Try(ConfigLoader.parseColor(text.getText.trim)).toEither.left.map(_ => s"'$label' bad color")

final class CompositionField(val label: String, initial: Vector[(String, Int)]) extends Field:
  private val text = JTextField(ConfigCodec.formatComposition(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[(String, Int)]] = ConfigCodec.parseComposition(text.getText)

final class WaypointsField(val label: String, initial: Vector[GridPos]) extends Field:
  private val text = JTextField(ConfigCodec.formatWaypoints(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[GridPos]] = ConfigCodec.parseWaypoints(text.getText)

final class NamesField(val label: String, initial: Vector[String]) extends Field:
  private val text = JTextField(ConfigCodec.formatNames(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[String]] = Right(ConfigCodec.parseNames(text.getText))

final class BranchesField(val label: String, initial: Vector[Branch]) extends Field:
  private val text = JTextField(ConfigCodec.formatBranches(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[Branch]] = ConfigCodec.parseBranches(text.getText)
```

Scalar sections (exemplar — economy; the others are symmetric):

```scala
private def economyFields(e: EconomyConfig): Vector[Field] = Vector(
  IntField("startGold", 0, Int.MaxValue, e.startGold),
  IntField("startLives", 1, Int.MaxValue, e.startLives),
  IntField("killReward", 0, Int.MaxValue, e.killReward),
  IntField("levelBonus", 0, Int.MaxValue, e.levelBonus)
)

private def readEconomy(fs: Vector[Field]): Either[String, EconomyConfig] =
  for
    g  <- fs(0).asInstanceOf[IntField].read
    l  <- fs(1).asInstanceOf[IntField].read
    kr <- fs(2).asInstanceOf[IntField].read
    lb <- fs(3).asInstanceOf[IntField].read
  yield EconomyConfig(g, l, kr, lb)
```

Collection sections are per-item field groups read back with `Vector` sequence.
Towers (exemplar; enemies/levels/biomes/twists/relics/augments/talents follow):

```scala
private def towerFields(t: TowerSpec): Vector[Field] = Vector(
  StringField("name", t.name),
  IntField("cost", 0, Int.MaxValue, t.cost),
  ColorField("color", t.color),
  FloatField("range", 0f, 1000f, t.range / cfg.board.cellPx),     // cells in the UI
  FloatField("fireInterval", 0.0001f, 1000f, t.fireInterval),
  FloatField("damage", 0f, 100000f, t.damage),
  FloatField("projectileSpeed", 0f, 100000f, t.projectileSpeed),
  EnumField("kind", DamageKind.values.map(_.toString).toVector, t.kind.toString),
  FloatField("splashRadius", 0f, 1000f, t.splashRadius / cfg.board.cellPx)
)

private def readTower(fs: Vector[Field]): Either[String, TowerSpec] =
  for
    name <- fs(0).asInstanceOf[StringField].read
    cost <- fs(1).asInstanceOf[IntField].read
    col  <- fs(2).asInstanceOf[ColorField].read
    rng  <- fs(3).asInstanceOf[FloatField].read
    fi   <- fs(4).asInstanceOf[FloatField].read
    dmg  <- fs(5).asInstanceOf[FloatField].read
    ps   <- fs(6).asInstanceOf[FloatField].read
    kind <- fs(7).asInstanceOf[EnumField].read
    spl  <- fs(8).asInstanceOf[FloatField].read
  yield TowerSpec(name, cost, col, rng * cfg.board.cellPx, fi, dmg, ps,
                  DamageKind.values.find(_.toString == kind).get, spl * cfg.board.cellPx)
```

**Enemies** flatten `resist` into five `FloatField`s (`resist.kinetic` .. `resist.explosion`,
range `[0,1]`) and add `EnumField("aura.kind", "None" +: AuraKind.values..., ...)`,
`FloatField("aura.radius", ...)`, `FloatField("aura.magnitude", ...)`,
`BoolField("heavy", ...)`, `EnumField("sidePreference", "None" +: RouteSide.values...)`.
On read, `aura.kind == "None"` => `aura = None`, `sidePreference == "None"` => `None`,
and `aura.radius` is multiplied by `cellPx`.

**Levels** render per level: `StringField("name")`, `WaypointsField("route.waypoints")`,
`BranchesField("route.branches")`, then one group per wave
(`IntField("count")`, `FloatField("spawnInterval")`, `FloatField("hpScale")`,
`CompositionField("composition")`). The wave count equals `level.waves.size`
(no add/remove in v1).

**Biomes/twists/relics/augments/talents** are simple `obj`-shaped records:
strings, enums (`Rarity`, `TalentLayer`, `DamageKind` for resist theme primary/
secondary), `NamesField` for `roster`/`prereqs`, `StringField` for `effect.kind`,
and a `FloatField` per effect param (edit the params map entry by entry;
params are shown as `kind` + a `names:values` text if desired — see Open
questions #3).

Save flow (the whole point — build, validate via re-parse, write, or reject):

```scala
private def onSave(): Unit =
  readAll() match
    case Left(err) =>
      JOptionPane.showMessageDialog(this, err, "Config Editor", JOptionPane.ERROR_MESSAGE)
    case Right(cfg) =>
      ConfigLoader.parse(ConfigWriter.toJson(cfg)) match
        case Left(err) =>
          JOptionPane.showMessageDialog(this, "Invalid config: " + err, "Config Editor", JOptionPane.ERROR_MESSAGE)
        case Right(_) =>
          ConfigWriter.write(cfg, ConfigLoader.FileName) match
            case Left(err) => JOptionPane.showMessageDialog(this, err, "Config Editor", JOptionPane.ERROR_MESSAGE)
            case Right(_)  => status.setText("Saved " + ConfigLoader.FileName)
```

`readAll()` sequences every section's `read` into a single
`Either[String, GameConfig]`. Startup: `ConfigLoader.load()` on `Right` builds
the form; on `Left` shows a warning dialog and builds the form from
`ConfigLoader.bundled()` (so the editor can still open a broken file and
overwrite it on Save). Reload re-reads and rebuilds the form.

### Step 6 — `build.sbt`: editor `mainClass`

```scala
lazy val editor = (project in file("editor"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "editor",
    Compile / mainClass := Some("td.ConfigEditorApp")
  )
```

No `fork := true` (see Decision 8). Delete or leave the `EditorMain` stub (it is
harmless; if left, it must not collide with `ConfigEditorApp` — it does not).

Files
-----
- `core/src/main/scala/td/ConfigWriter.scala` — new (serializer + `write` + `colorHex`).
- `core/src/main/scala/td/ConfigCodec.scala` — new (composition/waypoints/names/branches codecs).
- `core/src/main/scala/td/ConfigLoader.scala` — add `bundled()`.
- `core/src/test/scala/td/ConfigWriterTest.scala` — new.
- `core/src/test/scala/td/ConfigCodecTest.scala` — new.
- `editor/src/main/scala/td/ConfigEditorApp.scala` — new (Swing app).
- `build.sbt` — editor `Compile / mainClass`.

No changes to `Model.scala` / `GameConfig.scala` / `Config.scala` / `config.json`.

Verify
------
1. `sbt compile`.
2. `sbt test` — `ConfigWriterTest` (round-trip + cellPx division + colorHex) and
   `ConfigCodecTest` pass.
3. `sbt "editor/run"` (needs a display): window opens with all sections; edit a
   value, Save -> a `config.json` appears in the cwd and `sbt "game/run"` (or a
   headless `ConfigLoader.load()`) still loads it.
4. Invalid edit rejected: e.g. set a tower `kind` to nothing via a bad
   composition `Grunt:x`, or break wave `count` vs composition total, and Save ->
   error dialog, no file written.
5. `sbt "headless/run --config config.json"` still runs against the edited file.

Notes / Open questions
----------------------
1. Editor chrome uses plain string literals, not `Messages.t`, because `Messages`
   and `Config` both initialize from `ConfigLoader.initial()` and throw when the
   cwd config is broken (defeating a config-repair tool). If chrome localization
   is ever wanted, decouple `Messages`/`Config` from throwing init first.
2. v1 edits existing values only — no add/remove of towers, enemies, waves,
   talents, etc. Structural editing (resize lists) is a follow-up and would
   switch the collection UI to a `JTable`/list-plus-detail form.
3. Effect `params` ordering in written JSON is not stable past 4 entries
   (`Map[String,Float]`); harmless for loading, but if a designer diffs files,
   switch `params` to `Vector[(String, Float)]` (also gives the editor a stable
   per-param field order).
4. `params` are floats only; the editor renders each param as a `FloatField`.
   Editing the param KEY set (add/remove/rename params) is not supported in v1
   (matches task03's "kind + params" generic shape).
5. Branches are editable via a text codec (least ergonomic); acceptable since
   branch data is rare (one branch in the bundled config). Could become a
   dedicated sub-table later.
6. The editor never applies the config to a running game (`Config.reload` is the
   game's concern); it only writes the file. Confirmed by task scope ("Editor
   edits CONFIG, not save data").

Implementation gotchas (for the coding agent)
---------------------------------------------
- Use backticks for the enum constant: `JsonValue.ValueType.`object`` (`object`
  is a Scala keyword); `array`, `nullValue`, etc. are fine bare.
- `nullV` must be a `def` creating a FRESH node each call — a shared `JsonValue`
  reused across parents corrupts the tree (`next`/`prev` links).
- `num(f: Float)` MUST be `JsonValue(java.lang.Float.toString(f).toDouble)`, not
  `JsonValue(f)` / `JsonValue(f.toDouble)` — the latter widens and prints
  `0.05000000074505806`. The round-trip test guards this.
- `colorHex` uses `Math.round`, never `(int)` truncation, or 8-bit colors
  round-trip off-by-one.
- Reverse the `cellPx` scaling in exactly three places: `tower.range`,
  `tower.splashRadius`, `enemy.aura.radius`. Everything else is stored as the
  JSON value.
- The editor must build `GridPos`/model values WITHOUT calling `Config.game` or
  `Messages` (they throw on a broken cwd config). Use `ConfigLoader`/`ConfigWriter`/
  `ConfigCodec` + enum `.values` only. `ConfigWriterTest` may use `Config.game`
  (tests always have a valid bundled config).
- `JSpinner(SpinnerNumberModel(initial, min, max, 1))` — pass `Int` boxes;
  `spinner.getValue.asInstanceOf[Integer].intValue` to read.
- `DISPOSE_ON_CLOSE` on the frame, not `EXIT_ON_CLOSE` (would `System.exit` the
  sbt JVM since the editor is not forked).
- `sbt "editor/run"` needs a display (same as `game/run`); it is not part of
  `sbt test`.
