TASK 07 — PLAN (Knowledge & Journal: Sim + Persistence)
========================================================

Research
--------
Verified against the current codebase (task06 landed: `Enemy.resist` is a
layered field, `Hazard`/`World.hazards`/`World.leakDamage` exist, env rules in
`Sim`; `sbt compile`/`sbt test` pass, 36 tests):

- **`Enemy`** (`Model.scala:60-81`) carries `resist: Resistances` (the *layered*
  effective resist from `Sim.spawn`, task06), `tpe: String`, and `def spec:
  EnemySpec = Config.enemySpec(tpe)`. `EnemySpec` (`GameConfig.scala:25-34`)
  already has `aura: Option[Aura]` and `heavy: Boolean` — the "ability/affix"
  traits the journal must record. Route preference (side-taking) is deferred:
  no side data exists, and it lands with branches in phase 5 (task11); the
  trait-key convention already generalizes to it.
- **The single enemy construction site** is `Sim.spawn` (`Sim.scala:78-102`).
  Ability traits are therefore observable exactly once, at spawn.
- **Hits land in `Sim.moveProjectiles`** (`Sim.scala:322-382`). Each landed shot
  becomes a `Hit(id, kind, damage, ...)` (`Sim.scala:316`) via `resolveImpact`;
  splash/explosion produce one `Hit` *per target* (same kind). `tickEffects`
  (`Sim.scala:212-230`) applies burn/slow ticks but those are not discrete hits —
  discovery should key off landed `Hit`s only.
- **`World`** (`Model.scala:126-144`) already uses the "defaulted-field append"
  pattern (`won`/`kills`/`leaks`/`hazards`/`leakDamage`), so new accumulator
  fields are a non-breaking addition. `Sim.initial` (`Sim.scala:12-28`) is the
  only constructor and uses named args.
- **`Sim.step` purity** (`Sim.scala:30-40`) is already preserved by reading
  `Config.*` accessors; adding discovery events is a deterministic function of
  the stepped state, so the existing determinism test (`RngTest.scala:50-62`,
  which compares whole `World`s with `==`) stays green.
- **JSON persistence pattern** is `ConfigLoader` (`ConfigLoader.scala`):
  `JsonReader().parse` for reads (`:63-67`), `Source.fromFile`/classpath
  `getResourceAsStream` for source selection (`:38-44`), `File(path)` + `Either`
  for explicit paths (`:33-36`). There is currently **no JSON *writer*** in the
  repo — libGDX `JsonValue` (same dependency, headless-safe) builds/emits JSON
  via `addChild`/`prettyPrint`.
- **`.gitignore`** already lists `data/` — the intended home for save files, so
  the journal should live at `data/journal.json`, not in git.
- **`ConfigLoader.load(path)` returns `Left` for a missing file** (`:33-36`);
  the journal is different: "no journal yet" is the normal first-run state, so a
  missing file should mean *empty journal*, not an error.
- **The drain call sites** are `Main.scala` Playing phase (`:67-101`, incl. the
  fast-forward fold) and `HeadlessMain.run` (`:70-80`). `RunContext.biome`/
  `RunContext.twists` are the telegraphed biome/twist knowledge to bank at run
  start.

Decisions (confirmed)
---------------------
1. **Keep `Sim.step` pure; accumulate on `World`.** Add two defaulted fields:
   `events: Vector[DiscoveryEvent]` (drained by the caller each frame) and
   `traitHits: Map[(String, String), Int]` (per-run hit/observation counter that
   survives across frames, needed for the SIGHTING→STUDIED threshold). No
   `step`-returns-events signature change.
2. **Precision tiers are `Sighting` / `Studied`; UNKNOWN = absent from the
   journal.** Resists are tiered by hit count (first hit → Sighting, Nth hit →
   Studied, N = config). Ability/affix traits have no vague tier — they are
   *discovered exactly* on first observation (spawn), so they go straight to
   Studied with their exact value. This keeps the tier enum uniform while
   matching PLAN section 6 (tiers are described "per enemy + per damage kind").
3. **The journal records the enemy type's *intrinsic* traits (base resist from
   `EnemySpec`), not the run-layered effective resist.** The resist theme is
   telegraphed per-run and banked separately as biome knowledge; the enemy's own
   resist/aura/heavy are the discovered, run-independent "codex" entries.
   (task06 note-8's "effective value faced" is covered: theme is journaled via
   biome, base trait via the entry; the combined value is trivially derivable.)
4. **Trait keys are stable strings.** Resists: `"resist.Kinetic"`, `"resist.Fire"`,
   `"resist.Slow"`, `"resist.Splash"`, `"resist.Explosion"` (using
   `DamageKind.toString`, matching the config `kind` strings). Abilities:
   `"aura"`, `"heavy"`. Keys are the code↔journal↔config contract.
5. **`DiscoveryEvent.value` is a single string whose meaning depends on tier.**
   At `Sighting` it is a vague hint category (`"immune" | "resists" | "weak" |
   "neutral"`, derived from base resist, no exact number leaked); at `Studied` it
   is the exact value (`"0.35"`, `"SpeedBoost 0.25"`, `"true"`).
   task08 localizes/renders these; the event stays data (no `Messages.t` here).
6. **`studyHits` is a config value** under `combat` (N = 5), not a literal. It is
   tuning, so it belongs in `config.json` — the *journal data* stays out of
   config (task07 note).
7. **Journal is a save file at `data/journal.json`** (gitignored), with a bundled
   empty template `core/src/main/resources/journal.json` as the fresh-journal
   shape. `Journal.load(path)` returns `Right(empty)` for a missing file, `Left`
   on a corrupt one; `Journal.save` creates the parent dir. `Journal.record`
   does load→merge→save immediately (instant, un-gated).
8. **Discovery is emitted from `Sim` regardless of run outcome** (hits/kills fire
   events before win/loss is known). Merging into the journal is idempotent and
   monotonic: Sighting→Studied upgrades, never downgrades; duplicates no-op.
   Re-discovery across runs re-emits events (lets task08 show "NEW vs known");
   the journal itself never downgrades.
9. **Burn/slow ticks are not hits.** Only landed projectile `Hit`s (and spawn
   observations) emit discovery, matching "a hit of a kind".

Steps
-----

### Step 1 — `Model.scala`: `Precision` + `DiscoveryEvent` + `World` fields

Add next to `Phase` (bottom of the file):

```scala
/** How precisely a trait is known. UNKNOWN is the absence of a journal entry. */
enum Precision:
  case Sighting, Studied

/** A discovery surfaced from Sim during a step. [[value]] is the vague hint at
  * Sighting ("resists"/"neutral"/...), or the exact value at Studied ("0.35").
  */
case class DiscoveryEvent(enemy: String, traitKey: String, tier: Precision, value: String)
```

`World` gains two defaulted fields after `leakDamage`:

```scala
case class World(
    ...
    leaks: Int = 0,
    hazards: Vector[Hazard] = Vector.empty,
    leakDamage: Float = 0f,
    events: Vector[DiscoveryEvent] = Vector.empty,
    traitHits: Map[(String, String), Int] = Map.empty
):
```

`traitHits` keys are `(enemyType, traitKey)`; values are observation counts.

### Step 2 — `Config.scala` + `GameConfig.CombatConfig`: `studyHits`

`CombatConfig` gains `studyHits: Int` (append after `breakTime`); `ConfigLoader`
`parseRoot` adds `i(cmbt, "studyHits")` to the `CombatConfig(...)` construction;
`Config.scala` adds:

```scala
def StudyHits: Int = current.combat.studyHits
```

### Step 3 — `config.json`: `studyHits`

```json
"combat": {
  "maxStepDt": 0.05, "fastForward": 3, "hitRadius": 6.0,
  "burnDps": 6.0, "burnDuration": 3.0, "slowFactor": 0.5,
  "slowDuration": 2.0, "breakTime": 4.0, "studyHits": 5
}
```

### Step 4 — `Sim.scala`: emit discoveries

Add two private helpers near `killReward`:

```scala
private def vagueHint(base: Float): String =
  if base >= 0.99f then "immune"
  else if base > 0f then "resists"
  else if base < 0f then "weak"
  else "neutral"

private def bumpObserved(
    hits: Map[(String, String), Int],
    events: Vector[DiscoveryEvent],
    tpe: String,
    key: String,
    studiedAfter: Int,
    sightHint: String,
    studiedValue: String
): (Map[(String, String), Int], Vector[DiscoveryEvent]) =
  val n = hits.getOrElse((tpe, key), 0) + 1
  val ev: Option[DiscoveryEvent] =
    if n == 1 && studiedAfter == 1 then Some(DiscoveryEvent(tpe, key, Precision.Studied, studiedValue))
    else if n == 1 then Some(DiscoveryEvent(tpe, key, Precision.Sighting, sightHint))
    else if n == studiedAfter then Some(DiscoveryEvent(tpe, key, Precision.Studied, studiedValue))
    else None
  (hits + ((tpe, key) -> n), ev.fold(events)(events :+ _))
```

**(a) `spawn`** — observe ability traits once per enemy type, before the final
copy:

```scala
private def observeAbilities(w: World, spec: EnemySpec): World =
  val traits = Vector.newBuilder[(String, Int, String)]
  spec.aura.foreach(a => traits += (("aura", 1, s"${a.kind} ${a.magnitude}")))
  if spec.heavy then traits += (("heavy", 1, "true"))
  traits.result().foldLeft(w) { case (acc, (key, after, value)) =>
    val (hits, evs) = bumpObserved(acc.traitHits, acc.events, spec.name, key, after, "", value)
    acc.copy(traitHits = hits, events = evs)
  }
```

In `spawn`, after building `e`:

```scala
observeAbilities(w, spec).copy(
  enemies = w.enemies :+ e,
  spawnQueue = w.spawnQueue.tail,
  spawnTimer = Waves.spec(w.level, w.wave).spawnInterval,
  nextId = w.nextId + 1
)
```

**(b) `moveProjectiles`** — rename the landed-shot vector `val hits = hs.result()`
to `val landed = hs.result()` (and `hits.groupBy` → `landed.groupBy`), then after
`val (dead, alive) = damaged.partition(...)` fold discoveries over the landed
hits (using the pre-damage enemy type lookup) and include them in the final copy:

```scala
val idToTpe = w.enemies.map(e => e.id -> e.tpe).toMap
val (newHits, newEvents) = landed.foldLeft((w.traitHits, w.events)) {
  case ((hh, ee), h) =>
    idToTpe.get(h.id) match
      case None => (hh, ee)
      case Some(tpe) =>
        val base = Config.enemySpec(tpe).resist(h.kind)
        bumpObserved(hh, ee, tpe, s"resist.${h.kind}", Config.StudyHits, vagueHint(base), base.toString)
}

w.copy(
  shots = fs.result(),
  enemies = alive,
  gold = w.gold + math.round(dead.size * killReward(w)),
  kills = w.kills + dead.size,
  hazards = w.hazards ++ crushedHazards(w, dead),
  traitHits = newHits,
  events = newEvents
)
```

`traitHits`/`events` are NOT cleared in `completeLevel` — knowledge accumulates
across levels within a run; `Sim.initial` starts them empty.

### Step 5 — `Journal.scala` (new): model + load/save + record

```scala
package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.io.{File, PrintWriter}
import scala.io.Source

/** A single persisted codex entry: enemy type + trait + precision + optional
  * exact value (only when Studied; Sighting stores the vague hint there).
  */
case class JournalEntry(enemy: String, traitKey: String, tier: Precision, value: Option[String])

/** The persistent journal: discovered traits plus the telegraphed biome/twist
  * knowledge already seen. This is a save file, NOT tuning config.
  */
case class JournalData(entries: Vector[JournalEntry], biomes: Vector[String], twists: Vector[String]):
  def applyEvent(e: DiscoveryEvent): JournalData =
    val idx = entries.indexWhere(en => en.enemy == e.enemy && en.traitKey == e.traitKey)
    val value = if e.value.isEmpty then None else Some(e.value)
    if idx < 0 then copy(entries = entries :+ JournalEntry(e.enemy, e.traitKey, e.tier, value))
    else
      val cur = entries(idx)
      val next =
        if cur.tier == Precision.Sighting && e.tier == Precision.Studied then
          cur.copy(tier = Precision.Studied, value = value)
        else cur
      copy(entries = entries.updated(idx, next))

  def applyRun(run: RunContext): JournalData =
    copy(biomes = (biomes :+ run.biome).distinct, twists = (twists ++ run.twists).distinct)

object JournalData:
  val empty: JournalData = JournalData(Vector.empty, Vector.empty, Vector.empty)

object Journal:
  val FileName    = "journal.json"
  val defaultPath = s"data/$FileName"

  def load(): Either[String, JournalData] = load(defaultPath)

  def load(path: String): Either[String, JournalData] =
    val f = File(path)
    if !f.isFile then Right(JournalData.empty)
    else
      try Right(parse(JsonReader().parse(Source.fromFile(f).mkString)))
      catch case e: Exception => Left(s"journal parse error: ${e.getMessage}")

  def save(data: JournalData): Unit = save(defaultPath, data)

  def save(path: String, data: JournalData): Unit =
    val root    = JsonValue(JsonValue.ValueType.`object`)
    val entries = JsonValue(JsonValue.ValueType.array)
    data.entries.foreach { en =>
      val o = JsonValue(JsonValue.ValueType.`object`)
      o.addChild("enemy", JsonValue(en.enemy))
      o.addChild("trait", JsonValue(en.traitKey))
      o.addChild("tier", JsonValue(en.tier.toString))
      en.value.foreach(v => o.addChild("value", JsonValue(v)))
      entries.addChild(o)
    }
    root.addChild("entries", entries)
    root.addChild("biomes", strArray(data.biomes))
    root.addChild("twists", strArray(data.twists))
    val f = File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new PrintWriter(f)
    try w.write(root.prettyPrint(JsonWriter.OutputType.json, 2))
    finally w.close()

  def record(event: DiscoveryEvent): Unit =
    val data = load(defaultPath).getOrElse(JournalData.empty)
    save(defaultPath, data.applyEvent(event))

  def recordRun(run: RunContext): Unit =
    val data = load(defaultPath).getOrElse(JournalData.empty)
    save(defaultPath, data.applyRun(run))

  private def strArray(values: Vector[String]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    values.foreach(s => a.addChild(JsonValue(s)))
    a

  private def parse(root: JsonValue): JournalData =
    val entries = (0 until root.get("entries").size).map { i =>
      val v = root.get("entries").get(i)
      val valueVal = v.get("value")
      JournalEntry(
        enemy = v.getString("enemy"),
        traitKey = v.getString("trait"),
        tier = Precision.valueOf(v.getString("tier")),
        value = if valueVal == null || valueVal.asString().isEmpty then None else Some(valueVal.asString())
      )
    }.toVector
    JournalData(entries, strArray(root, "biomes"), strArray(root, "twists"))

  private def strArray(root: JsonValue, name: String): Vector[String] =
    val child = root.get(name)
    if child == null || !child.isArray then Vector.empty
    else (0 until child.size).map(i => child.get(i).asString()).toVector
```

### Step 6 — `core/src/main/resources/journal.json` (empty template)

```json
{ "entries": [], "biomes": [], "twists": [] }
```

### Step 7 — `Main.scala` + `HeadlessMain.scala`: drain + bank run knowledge

`Main.scala` — bank the run's telegraphed knowledge at start, drain events after
each frame's step(s):

```scala
private def newRun(): Phase =
  fast = false
  val w = Sim.initial(System.nanoTime())
  Journal.recordRun(w.run)
  Phase.Playing(w)

private def startTutorial(): Phase =
  fast = false
  val w = Sim.initial(RunGen.tutorial(System.nanoTime()))
  Journal.recordRun(w.run)
  Phase.Playing(w)
```

In the Playing phase, after the fast-forward fold:

```scala
val first = Sim.step(w, dt, cmds)
val next  = (1 until steps).foldLeft(first)((acc, _) => Sim.step(acc, dt, Vector.empty))
next.events.foreach(Journal.record)
val drained = next.copy(events = Vector.empty)
gfx.drawGame(drained, hover, fast, sel, notice)
// ... phase transitions use `drained` in place of `next`
```

`HeadlessMain.scala` — same pattern inside the run loop (bank `world.run` once
before the loop, drain `world.events` after each step). This makes "journal
persists across separate processes" verifiable by running headless twice.

### Step 8 — Tests: `core/src/test/scala/td/JournalTest.scala` (new)

Use `java.nio.file.Files.createTempFile` for explicit paths (never `data/`).

```scala
package td

import java.nio.file.Files

class JournalTest extends munit.FunSuite:
  private def tmpPath(suffix: String): String =
    Files.createTempFile(s"journal-$suffix", ".json").toString

  test("missing file loads as empty journal") {
    assertEquals(Journal.load(tmpPath("missing")), Right(JournalData.empty))
  }

  test("save/load round-trips entries, biomes, twists") {
    val path = tmpPath("roundtrip")
    val data = JournalData(
      entries = Vector(JournalEntry("Brute", "resist.Kinetic", Precision.Studied, Some("0.35")),
                       JournalEntry("Ember", "resist.Fire", Precision.Sighting, Some("resists"))),
      biomes = Vector("cube-farm"),
      twists = Vector("brittle")
    )
    Journal.save(path, data)
    assertEquals(Journal.load(path), Right(data))
  }

  test("applyEvent upgrades Sighting to Studied and never downgrades") {
    val d1 = JournalData.empty.applyEvent(DiscoveryEvent("Brute", "resist.Kinetic", Precision.Sighting, "resists"))
    assertEquals(d1.entries.head.tier, Precision.Sighting)
    val d2 = d1.applyEvent(DiscoveryEvent("Brute", "resist.Kinetic", Precision.Studied, "0.35"))
    assertEquals(d2.entries.head.tier, Precision.Studied)
    assertEquals(d2.entries.head.value, Some("0.35"))
    val d3 = d2.applyEvent(DiscoveryEvent("Brute", "resist.Kinetic", Precision.Sighting, "resists"))
    assertEquals(d3.entries.head.tier, Precision.Studied)
  }

  test("applyRun dedups biome and twist ids") {
    val run = RunContext(seed = 1L, biome = "cube-farm", twists = Vector("brittle"))
    val d   = JournalData.empty.applyRun(run).applyRun(run)
    assertEquals(d.biomes, Vector("cube-farm"))
    assertEquals(d.twists, Vector("brittle"))
  }

  test("first hit emits Sighting; Nth hit emits Studied with the exact value") {
    val collected = fire("Brute", DamageKind.Kinetic, Config.StudyHits)
    assert(collected.exists(e => e.tier == Precision.Sighting && e.value == "resists"))
    assert(collected.exists(e => e.tier == Precision.Studied && e.value == "0.35"))
  }

  test("spawning an enemy with an aura records its ability as Studied") {
    val run = RunContext(seed = 1L, biome = "tutorial",
      resistTheme = Config.biome("tutorial").resistTheme, roster = Vector("Commander"))
    val w = Sim.initial(run).copy(spawnQueue = Vector("Commander"), spawnTimer = 0f, breakTimer = 0f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assert(stepped.enemies.exists(_.tpe == "Commander"))
    assert(stepped.events.exists(e => e.enemy == "Commander" && e.traitKey == "aura" && e.tier == Precision.Studied))
  }

  private def fire(enemyTpe: String, kind: DamageKind, n: Int): Vector[DiscoveryEvent] =
    val b    = Config.biome("tutorial")
    val run  = RunContext(seed = 1L, biome = b.id, resistTheme = b.resistTheme, roster = b.roster)
    val e = Enemy(id = 1L, progress = 0f, hp = 1e6f, maxHp = 1e6f, speed = 42f,
      tpe = enemyTpe, level = 0, resist = Config.enemySpec(enemyTpe).resist)
    var w = Sim.initial(run).copy(spawnQueue = Vector.empty, breakTimer = 999f)
    var out = Vector.empty[DiscoveryEvent]
    for _ <- 0 until n do
      val (x, y) = e.pos
      val p = Projectile(x, y, 1L, kind, 1f, 1e6f, 0f, None)
      w = Sim.step(w.copy(enemies = Vector(e), shots = Vector(p)), Config.MaxStepDt, Vector.empty)
      out = out ++ w.events
      w = w.copy(events = Vector.empty)
    out
```

(`Brute` base kinetic resist is `0.35` — see `config.json:28` — so the vague
hint is `"resists"` and the exact value `"0.35"`. `Config.StudyHits` = 5.)

Files
-----
- `core/src/main/scala/td/Model.scala` — `Precision`, `DiscoveryEvent`,
  `World.events` + `World.traitHits`.
- `core/src/main/scala/td/GameConfig.scala` — `CombatConfig.studyHits: Int`.
- `core/src/main/scala/td/ConfigLoader.scala` — parse `studyHits`.
- `core/src/main/scala/td/Config.scala` — `StudyHits` accessor.
- `core/src/main/scala/td/Sim.scala` — `bumpObserved`/`vagueHint`/`observeAbilities`
  helpers; discovery emission in `spawn` and `moveProjectiles`.
- `core/src/main/scala/td/Journal.scala` — NEW: `JournalEntry`, `JournalData`,
  `Journal` (load/save/record/recordRun).
- `core/src/main/resources/config.json` — `combat.studyHits`.
- `core/src/main/resources/journal.json` — NEW: empty template.
- `core/src/test/scala/td/JournalTest.scala` — NEW: munit tests.
- `game/src/main/scala/td/Main.scala` — bank run knowledge + drain events.
- `headless/src/main/scala/td/HeadlessMain.scala` — same (cross-process demo).

No changes to `Grid.scala`, `Waves.scala`, `RunGen.scala`, `Rng.scala`,
`Bot.scala`, `Render.scala`, or `Messages.scala`.

Verify
------
1. `sbt compile`.
2. `sbt test` — existing 36 tests still pass (the new `World` fields are
   defaulted; determinism test compares whole `World`s, and `events`/`traitHits`
   evolve deterministically), plus `JournalTest`.
3. Discovery wiring: `JournalTest` asserts Sighting after 1 hit and Studied
   (with exact value) after `Config.StudyHits` hits, and ability discovery at
   spawn — all headless, no window.
4. Persistence across processes: run `sbt "headless/run --seed N"` twice; the
   second run reads the `data/journal.json` written by the first (bot builds
   towers → hits → discoveries). Inspect `data/journal.json` to confirm
   Sighting/Studied entries accumulate and never downgrade.
5. Optional manual: `sbt "game/run"` (needs a display) — journal file appears
   under `data/` after a run; UI rendering of discoveries is explicitly out of
   scope (task08).

Notes / Open questions
----------------------
1. **Base resist, not layered**, is the studied value (Decision 3). If a future
   design wants "the exact value faced", switch `moveProjectiles`'s `base` to
   `e.resist(h.kind)` (available on the enemy) — a one-line change; the journal
   schema is unchanged.
2. **"weak to X" is future-proofing only** — resistances are authored ≥ 0 today,
   so `vagueHint` returns `"resists"`/`"neutral"`/`"immune"` in practice; the
   `"weak"` branch activates if a negative resist is ever configured.
3. **`record` does load→merge→save per event.** At tower-defense event volume
   (tens of discoveries/run) this is fine and satisfies "instant, un-gated". A
   future in-memory cache + flush could batch it; keep the file the source of
   truth so a crash mid-run loses nothing banked.
4. **Corrupt `journal.json`** → `load` returns `Left`, `record` treats it as
   empty (fresh start). Acceptable for a save file now; note it if data loss
   matters later.
5. **`vagueHint` categories and the exact-value format** (`"0.35"`, `"SpeedBoost
   0.25"`) are the contract task08 localizes via `Messages.t` — the event itself
   stays a machine string (context.md section 9: data, not UI).
6. **Re-discovery across runs** re-emits Sighting/Studied events every run (Sim
   is journal-agnostic). The journal merge is monotonic so this is harmless and
   is exactly what task08 needs to distinguish "NEW" from "already known".

Implementation gotchas (added during review)
--------------------------------------------
- **`DiscoveryEvent`/`Precision` live in `Model.scala`** so `World.events` types
  cleanly; `Journal.scala` imports them via the shared `td` package (Scala 3
  resolves top-level types across files, like `ResistTheme` in `GameConfig.scala`).
- **Rename the landed-shot vector** in `moveProjectiles` from `hits` to `landed`
  (`Sim.scala:348`) so it doesn't collide with the new `traitHits` counter; the
  existing `hits.groupBy(_.id)` / `hitsById.get` references must be updated too.
- **Discovery must use the pre-damage enemy type** (`w.enemies`, not the
  post-damage `alive`) so a killing hit still records the enemy's trait; build
  `idToTpe` from `w.enemies` before the `partition`.
- **`spawn` uses `observeAbilities(w, spec).copy(...)`**, i.e. fold into the
  *pre-spawn* `w` so `traitHits`/`events` are carried along with the new enemy —
  don't observe against the already-copied world or events get dropped.
- **Do not clear `traitHits` in `completeLevel`** (N hits can span levels);
  clear only `events` at the drain site in Main/Headless, and let `Sim.initial`
  start both empty.
- **`studyHits` parse uses `i(cmbt, "studyHits")`** (Int), not `f(...)`; the rest
  of `CombatConfig` is Float but this one is a count.
- **`JsonValue` writing** — verify the exact libGDX API at implementation time:
  `prettyPrint(JsonWriter.OutputType.json, 2)` (or `toJson`) for output and
  `addChild(name, JsonValue)` for building; both are headless-safe (no GL). If a
  method signature differs, a plain string-builder writer is an acceptable
  fallback.
- **Tests use `Files.createTempFile`, never `data/`** — keep the working tree
  clean and parallel-safe; the game/headless write to `data/` (gitignored) only.
- **`vagueHint` thresholds** (0.99 for "immune") mirror `Resistances.layered`'s
  clamp-to-1.0 convention; keep them in sync if that convention changes.
