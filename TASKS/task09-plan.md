TASK 09 — PLAN (Meta Progression: Currency + Talent Tree)
=========================================================

Research
--------
Verified against the current codebase:

- `Model.scala` `RunContext` already carries the per-run id vectors (`biome`,
  `resistTheme`, `twists`, `roster`, `mods`, `loadout`, `relics`, `augments`)
  and `World.run: RunContext`. There is NO field yet for owned talents.
- `Config.scala` already exposes `talents: Vector[Talent]` and `talent(id)`
  (task03), so no new config accessors are needed for the tree itself.
- `GameConfig.scala` defines `Talent(id, name, layer: TalentLayer, cost,
  prereqs, effect: Effect)` with `Effect(kind: String, params: Map[String,
  Float])`; the tree data (3 fillers, 3 cores, 3 deeps) is in `config.json`
  already, with prereqs encoding the layer order (filler -> core -> deep).
- `ConfigLoader.scala` `parseRoot` reads every scalar block via `obj(root, ...)`
  and requires them all (economy/combat/i18n are mandatory). `ConfigTest`
  builds a `minimalConfig(...)` JSON string for negative cases, so a new
  required section must be added to that fixture too.
- `Journal.scala` is the reference persistence pattern (`JsonReader`/`JsonValue`
  + `JsonWriter.prettyPrint`, `data/<file>.json`, missing file -> empty, parse
  error -> `Left(msg)`). `Meta` should mirror it exactly.
- `Sim.scala` computes `killReward(w)` (Float), the level bonus
  (`w.gold + Config.LevelBonus`) in `completeLevel`, and tower range/damage in
  `fireTowers` — these are the three injection points for filler stat bonuses.
- `Main.scala` `newRun`/`startTutorial` build the run via `Sim.initial(...)`;
  `toRecap(w)` is the single run-end site (called once when `won || isOver`).
- Tests: `ConfigTest` (schema), `JournalTest` (persistence + `Sim.step`
  determinism patterns), `SimSmokeTest` (leak-to-lose loop). New logic must ship
  a headless munit test (context.md §5).

Decisions (confirmed)
---------------------
1. `RunContext` gains `talents: Vector[String] = Vector.empty` (owned talent ids
   snapshotted at run start). `Sim` reads `w.run.talents`; it never touches
   disk. The tree stays pure and headlessly testable.
2. **Every tunable value is data.** Payout tuning lives in a new `meta` config
   section (`MetaConfig` in `GameConfig`, parsed by `ConfigLoader`, exposed via
   `Config` accessors, authored in `config.json`). No numeric payout constants
   in code.
3. "Layer gating" = the prereq graph already in `config.json` (fillers have no
   prereqs; cores require fillers; deeps require cores). `purchase` enforces
   prereqs-are-owned; no new config field is invented.
4. Cores are NOT exclusive: `purchase` never rejects a second core, and the
   effects summary exposes the full set of active cores.
5. Active-effects summary is a single `TalentEffects` case class, computed
   purely from `Vector[String]` talent ids + `Config.talents`. It covers the
   filler stat bonuses (damage/range/gold) plus forward-looking flags
   (`loadoutSlots`, `rerollUnlocked`) and `activeCores`/`activeDeeps` for
   task10's relic gating. The effect *values* come from config; only the
   kind->field mapping (engine) is code, matching the existing biome-env kind
   matching in `Sim`.
6. Persistence follows `Journal`: `meta.json` under `data/`, shape
   `{ "balance": <int>, "talents": ["id", ...] }`, missing file -> empty, bad
   file -> `Left`.

Steps
-----

### Step 1 — `Model.scala`: `RunContext.talents`

Add one field (defaulted so every existing construction site still compiles):

```scala
case class RunContext(
    seed: Long,
    biome: String = "tutorial",
    resistTheme: ResistTheme = ResistTheme(DamageKind.Kinetic, 0f, DamageKind.Fire, 0f),
    twists: Vector[String] = Vector.empty,
    roster: Vector[String] = Vector.empty,
    mods: Vector[String] = Vector.empty,
    loadout: Vector[String] = Vector.empty,
    relics: Vector[String] = Vector.empty,
    augments: Vector[String] = Vector.empty,
    talents: Vector[String] = Vector.empty
)
```

### Step 2 — Config schema for the payout economy (data-driven)

`GameConfig.scala` — new case class + field:

```scala
/** Out-of-run meta-progression economy: how a finished run converts progress
  * into permanent currency.
  */
case class MetaConfig(payoutPerLevel: Int, payoutPerWave: Int, payoutWinBonus: Int)
```

Append `meta: MetaConfig` to `GameConfig`.

`ConfigLoader.scala` — parse it in `parseRoot` (mandatory, like economy):

```scala
val meta = obj(root, "meta")
...
meta = MetaConfig(i(meta, "payoutPerLevel"), i(meta, "payoutPerWave"), i(meta, "payoutWinBonus")),
```

`Config.scala` — accessors (place near the economy block):

```scala
// -- meta progression --------------------------------------------------------
def PayoutPerLevel: Int = current.meta.payoutPerLevel
def PayoutPerWave: Int  = current.meta.payoutPerWave
def PayoutWinBonus: Int = current.meta.payoutWinBonus
```

`config.json` — new section (after `"economy"`):

```json
  "meta": { "payoutPerLevel": 10, "payoutPerWave": 2, "payoutWinBonus": 25 },
```

`ConfigTest.scala` — add the section to `minimalConfig` and a positive
assertion (see Step 6).

### Step 3 — `core/src/main/scala/td/Meta.scala` (new)

Mirror `Journal` (package `td`, libGDX `JsonReader`/`JsonValue`/`JsonWriter`,
`java.io` + `scala.io.Source`). No numeric literals — payout reads `Config`.

```scala
package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.io.{File, PrintWriter}
import scala.io.Source

/** Permanent meta-progression: currency balance + owned talent ids. */
case class MetaData(balance: Int, talents: Vector[String])

object MetaData:
  val empty: MetaData = MetaData(0, Vector.empty)

/** Stat/flag effects of a set of owned talents, resolved against config. */
case class TalentEffects(
    owned: Set[String],
    damageMult: Float,
    rangeMult: Float,
    goldMult: Float,
    loadoutSlots: Int,
    rerollUnlocked: Boolean
):
  def owns(id: String): Boolean = owned.contains(id)
  def activeCores: Set[String] = layerIds(TalentLayer.Core)
  def activeDeeps: Set[String] = layerIds(TalentLayer.Deep)
  private def layerIds(layer: TalentLayer): Set[String] =
    Config.talents.filter(_.layer == layer).map(_.id).toSet & owned

object TalentEffects:
  val none: TalentEffects = TalentEffects(Set.empty, 1f, 1f, 1f, 0, false)

object Meta:
  val FileName    = "meta.json"
  val defaultPath = s"data/$FileName"

  // -- persistence (mirrors Journal) -----------------------------------------
  def load(): Either[String, MetaData] = load(defaultPath)
  def load(path: String): Either[String, MetaData] =
    val f = File(path)
    if !f.isFile then Right(MetaData.empty)
    else
      try Right(parse(JsonReader().parse(Source.fromFile(f).mkString)))
      catch case e: Exception => Left(s"meta parse error: ${e.getMessage}")

  def save(data: MetaData): Unit = save(defaultPath, data)
  def save(path: String, data: MetaData): Unit =
    val root = JsonValue(JsonValue.ValueType.`object`)
    root.addChild("balance", JsonValue(data.balance))
    root.addChild("talents", strArray(data.talents))
    val f = File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new PrintWriter(f)
    try w.write(root.prettyPrint(JsonWriter.OutputType.json, 2))
    finally w.close()

  def active(): MetaData = load().getOrElse(MetaData.empty)

  private def parse(root: JsonValue): MetaData =
    MetaData(root.getInt("balance", 0), strArray(root, "talents"))

  private def strArray(root: JsonValue, name: String): Vector[String] =
    val child = root.get(name)
    if child == null || !child.isArray then Vector.empty
    else (0 until child.size).map(i => child.get(i).asString()).toVector

  private def strArray(values: Vector[String]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    values.foreach(s => a.addChild(JsonValue(s)))
    a

  // -- payout -----------------------------------------------------------------
  /** Currency earned at run end, scaled by progress; win adds a flat bonus.
    * Every number comes from the `meta` config section.
    */
  def payout(w: World): Int =
    val levels = if w.won then Config.levelCount else w.level
    val base   = levels * Config.PayoutPerLevel + w.wave * Config.PayoutPerWave
    val bonus  = if w.won then Config.PayoutWinBonus else 0
    math.max(0, base + bonus)

  def bank(w: World, data: MetaData): MetaData =
    data.copy(balance = data.balance + payout(w))

  def bankRun(w: World): MetaData =
    val data = load().getOrElse(MetaData.empty)
    val next = bank(w, data)
    save(defaultPath, next)
    next

  // -- talent tree ------------------------------------------------------------
  /** Aggregate active effects from owned talent ids (pure, no IO). */
  def effects(talents: Vector[String]): TalentEffects =
    talents.foldLeft(TalentEffects.none) { (acc, id) =>
      Config.talents.find(_.id == id).fold(acc)(t => add(acc, t))
    }

  private def add(acc: TalentEffects, t: Talent): TalentEffects =
    val owned = acc.owned + t.id
    val p     = t.effect.params
    t.effect.kind match
      case "damageBonus"  => acc.copy(owned = owned, damageMult = acc.damageMult + p.getOrElse("mult", 0f))
      case "rangeBonus"   => acc.copy(owned = owned, rangeMult  = acc.rangeMult  + p.getOrElse("mult", 0f))
      case "goldBonus"    => acc.copy(owned = owned, goldMult   = acc.goldMult   + p.getOrElse("mult", 0f))
      case "loadoutSlots" => acc.copy(owned = owned, loadoutSlots = acc.loadoutSlots + p.getOrElse("slots", 0f).toInt)
      case "reroll"       => acc.copy(owned = owned, rerollUnlocked = true)
      case _              => acc.copy(owned = owned)

  /** Pure purchase: validates id, duplicates, prereqs (layer gating), cost. */
  def purchase(data: MetaData, id: String): Either[String, MetaData] =
    Config.talents.find(_.id == id) match
      case None => Left(s"unknown talent '$id'")
      case Some(t) =>
        if data.talents.contains(id) then Left(s"talent '$id' already purchased")
        else
          val missing = t.prereqs.filterNot(data.talents.contains)
          if missing.nonEmpty then Left(s"talent '$id' requires: ${missing.mkString(", ")}")
          else if data.balance < t.cost then Left(s"talent '$id' costs ${t.cost}, balance is ${data.balance}")
          else Right(data.copy(balance = data.balance - t.cost, talents = data.talents :+ id))

  /** Headless/scriptable purchase: load -> purchase -> save. */
  def purchase(id: String): Either[String, MetaData] =
    val data = load().getOrElse(MetaData.empty)
    purchase(data, id).map { d =>
      save(defaultPath, d)
      d
    }
```

### Step 4 — `Sim.scala`: apply filler effects

Add a helper and use it at the three injection points:

```scala
private def effects(w: World): TalentEffects = Meta.effects(w.run.talents)
```

- `killReward` (line 73): multiply by goldMult:
  `(Config.KillReward + envParam(w, "recycling", "killGoldBonus")) * effects(w).goldMult`
- `completeLevel` (line 208): `gold = w.gold + math.round(Config.LevelBonus * effects(w).goldMult),`
- `fireTowers` (line 327): compute `val eff = effects(w)` once, then
  `val range = spec.range * eff.rangeMult; val range2 = range * range` and
  projectile `damage = spec.damage * eff.damageMult`.

`Sim.step` stays pure: `w.run.talents` is part of the immutable World snapshot;
no disk/randomness is read inside a step.

### Step 5 — `game/src/main/scala/td/Main.scala`: wire meta into a run

- `newRun`/`startTutorial`: attach active talents to the rolled run:
  `Sim.initial(RunGen.roll(System.nanoTime()).copy(talents = Meta.active().talents))`
  (and the tutorial equivalent).
- `toRecap`: bank currency exactly once at run end, before the recap is built:
  `Meta.bankRun(w)` as the first line.

### Step 6 — Tests

`ConfigTest.scala` — add the `meta` block to `minimalConfig(...)` and a positive
check that the bundled values load (pins the data-driven contract):

```scala
test("meta payout economy loads from config") {
  assertEquals(cfg.meta.payoutPerLevel, 10)
  assertEquals(cfg.meta.payoutPerWave, 2)
  assertEquals(cfg.meta.payoutWinBonus, 25)
}
```

`core/src/test/scala/td/MetaTest.scala` (new) — headless munit, temp file paths
(never `Meta.defaultPath`):

1. `missing meta.json loads as empty`.
2. `save/load round-trips balance + talents`.
3. `payout` uses config: lost world `(level=1, wave=3)` ->
   `1*Config.PayoutPerLevel + 3*Config.PayoutPerWave`; won world adds
   `Config.PayoutWinBonus`.
4. `losing a run banks partial currency`: step `Sim.initial(RunGen.tutorial(1L))`
   until `isOver`, assert `Meta.bank(w, MetaData.empty).balance > 0`.
5. `core purchase requires its filler prereq`: `purchase(empty, "pyromancer")`
   is `Left`; after `filler-damage` it is `Right`.
6. `purchase deducts cost and rejects duplicates`.
7. `multiple cores can be active at once`: buy `filler-damage`, `pyromancer`,
   `breaker`; `effects(...).activeCores` contains both cores.
8. `effects` aggregates fillers from config: `damageBonus -> 1.1`,
   `rangeBonus -> 1.1`, `goldBonus -> 1.15`; cores/deeps land in `owned` with no
   stat change.
9. `damageBonus multiplies tower shot damage` (Sim wiring): build a World with a
   `Basic` tower in range of a stationary enemy, step, assert
   `shots.head.damage == 12f` without the talent and `12f * 1.1f` with it.
10. `goldBonus increases kill reward` (Sim wiring): kill a 1-hp enemy with a
    kinetic projectile, assert gold gained `== Config.KillReward` base vs
    `math.round(Config.KillReward * 1.15f)` with `filler-gold`.

Files
-----
- `core/src/main/scala/td/Meta.scala` — NEW (`MetaData`, `TalentEffects`, `Meta`).
- `core/src/main/scala/td/GameConfig.scala` — `MetaConfig` + `GameConfig.meta`.
- `core/src/main/scala/td/ConfigLoader.scala` — parse the `meta` section.
- `core/src/main/scala/td/Config.scala` — `PayoutPerLevel/PerWave/WinBonus`.
- `core/src/main/resources/config.json` — new `meta` section.
- `core/src/main/scala/td/Model.scala` — `RunContext.talents`.
- `core/src/main/scala/td/Sim.scala` — apply damage/range/gold effects.
- `game/src/main/scala/td/Main.scala` — attach talents at run start; bank at run end.
- `core/src/test/scala/td/MetaTest.scala` — NEW tests.
- `core/src/test/scala/td/ConfigTest.scala` — `minimalConfig` + `meta` assertion.

Verify
------
1. `sbt compile`.
2. `sbt test` — existing suite plus `MetaTest` and the `ConfigTest` addition
   (headless-safe).
3. Optional manual: `sbt "game/run"` (needs a display) — win/lose, relaunch,
   confirm `data/meta.json` persists and currency accumulates.

Notes / Open questions
----------------------
1. `MetaConfig` is required (like economy/combat), so `ConfigTest.minimalConfig`
   must gain the `meta` block or the negative-parse tests break.
2. `effects` treats repeated same-kind fillers additively (`1f + sum(mult)`);
   there is currently one talent per kind, so this is forward-looking.
3. `rerollUnlocked`/`loadoutSlots` are computed but no talent grants them yet —
   task10 (reroll talent) and task14 (loadout slots) will add the data. The
   summary shape is ready for them.
4. Layer-gating is realized purely through the existing prereq graph; no extra
   config field or load-time layer-ordering validation is added. Add a
   `ConfigLoader` invariant (prereq layer < node layer) if the tree ever needs
   to defend against malformed data.
5. `Meta.bankRun` mutates `data/meta.json` on disk. The game calls it once per
   run end (`toRecap`); tests never touch `defaultPath`.
6. The `add` matcher's effect-kind strings (`"damageBonus"`, `"mult"`, ...) are
   the data contract keys, not tunables — the values themselves are in
   `config.json`. This mirrors `Sim`'s existing biome-env `kind` matching.
