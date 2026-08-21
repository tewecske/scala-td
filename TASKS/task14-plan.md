TASK 14 — PLAN (Polish: naming, colors, briefing, tutorial)
===========================================================

Research
--------
Verified against the current codebase (all tasks 01–13 committed):

- **`Phase`** (`Model.scala:193-207`) has only `Menu`, `Playing`, `Paused`, `Recap`.
  There is **no** `Briefing`, `Loadout`, or `Talents` phase — all three are new.
- **`RunContext`** already carries `loadout: Vector[String]` (`Model.scala:143`) and
  `biome`, `resistTheme`, `twists`, `talents`. But `RunGen.roll`/`RunGen.tutorial`
  (`RunGen.scala:28-57`) never set `loadout`, so it is always empty today, and
  **nothing reads it**: `Sim.canBuild` (`Sim.scala:93-97`) only checks gold +
  buildability + occupancy. The loadout is dead data — task14 wires it up.
- **`TalentEffects.loadoutSlots`** already exists (`Meta.scala:19`) and the `add`
  matcher already handles an `"loadoutSlots"` effect kind (`Meta.scala:103`), but
  **no talent grants it** and there is no base-slot count anywhere. A loadout
  picker needs both a base value (config) and a talent (data).
- **Briefing content is already in the model**, telegraphed only: `Biome` has
  `name`, `environment: Option[Effect]`, `resistTheme` (`GameConfig.scala:150-156`);
  `RunContext` has `resistTheme` + `twists`. `Main.deathRecap` already resolves
  resist kinds, and `Render.drawHud` already renders twist names + `twist.*` copy
  (`Render.scala:451-456`). No briefing screen renders any of it yet.
- **The tutorial is NOT actually 2 levels.** `RunGen.tutorial` sets
  `biome = "tutorial"` + no twists, but the sim then plays the shared 3-level
  `Config.levels` table. The "2 fixed levels, always identical, skippable" locked
  decision is only half-implemented (identical + no twists yes; 2-level content +
  skippable no).
- **Level set is global, not biome-aware.** `Sim.completeLevel` uses
  `Config.levelCount` (`Sim.scala:313`), `Waves.waveCount`/`Waves.spec` use
  `Config.levelAt(level)` (`Waves.scala:16,21`), `Meta.payout` uses
  `Config.levelCount` (`Meta.scala:75`), `Render.drawHud` uses `Config.levelCount`
  + `Config.levelAt` (`Render.scala:449`), and `HeadlessMain.summary` uses
  `Config.levelCount` (`HeadlessMain.scala:86`). Giving the tutorial its own 2
  levels means making all of these resolve the level list from `run.biome`.
- **Meta/talent UI does not exist** (task09 explicitly deferred it: "Don't build
  the talent-tree UI screen yet (task14 polish)"). `Meta.purchase(data, id): Either`
  (`Meta.scala:108-117`) and `Meta.effects` (`Meta.scala:91-94`) are pure and
  headless-ready; only the screen + save wiring are missing.
- **Naming is off-theme.** Towers are `Basic/Fire/Frost/Cannon/Bombard`, enemies
  `Grunt/Swift/Brute/Ember/Frostling/Commander/Shieldbearer/Edgelord`, levels
  `Verdant Valley/Ashen Ridge/The Citadel`. Only `Edgelord` and the talents/
  relics/augments are already square/box themed. Names are **data** in `config.json`,
  but are pinned by cross-references: `twist.targetTower` (`config.json:136-143`),
  biome `roster`s, wave `composition`s, and a wide net of tests (`MetaTest`,
  `TwistTest`, `DraftTest`, `EnvRulesTest`, `BranchTest`, `JournalTest`,
  `ConfigCodecTest`, `ConfigTest`). The window title `"Tower Defense"`
  (`Main.scala:9`) and the menu title `"TOWER DEFENSE"` (`Render.scala:188`) are
  also off-theme.
- **`ConfigLoader` requires every scalar section** via `obj(root, ...)`
  (`ConfigLoader.scala:113-117`) and requires `levels` non-empty
  (`ConfigLoader.scala:86`). Adding a `loadout` scalar section and a
  `tutorialLevels` array means **three inline test fixtures must gain the new
  sections**: `ConfigTest.minimalConfig` (`ConfigTest.scala:71-108`) and
  `BranchTest.routeJson` (`BranchTest.scala:121-151`), plus the editor's
  `ConfigWriter.root` (`ConfigWriter.scala:50-67`) must serialize them or
  `ConfigWriterTest`'s round-trip breaks.
- **Colors are already 100% data** (`Colors` in `GameConfig.scala:101-124`,
  parsed by `ConfigLoader.parseColors`, exposed via `Config.*Color`). The "color
  pass" is therefore a *usage* review (HUD contrast) plus reusing existing
  `Config.*Color` for the new screens — no schema change expected.

Decisions (confirmed)
---------------------
1. **Three new `Phase` cases, all carrying pure data** (no IO in a phase):
   `Briefing(run: RunContext)`, `Loadout(run: RunContext, selected: Vector[String])`,
   `Talents(data: MetaData)`. Screen routing lives in `Main`; drawing in `Render`.
2. **Loadout gating is opt-in by emptiness.** `Sim.canBuild` additionally checks
   `w.run.loadout.isEmpty || w.run.loadout.contains(tpe)`. Empty = unrestricted
   (the headless `Bot` and existing tests construct runs without a loadout and
   keep working). The picker always writes a non-empty vector. This keeps
   `canBuild` the single source of truth (context.md §3) without breaking
   `Bot`/tests.
3. **Loadout slot count = `Config.BaseLoadoutSlots + TalentEffects.loadoutSlots`.**
   New `loadout` config section (`LoadoutConfig(baseSlots: Int)`, data) + one new
   `loadoutSlots` filler talent in `config.json` (data). `Meta.effects` is
   unchanged — the `add` matcher already sums `loadoutSlots`.
4. **Tutorial levels are content data, not special-cased code.** New top-level
   `tutorialLevels` array (2 `LevelConfig`s) in `config.json` + a
   `Config.levelsFor(biome)`/`levelCountFor`/`levelAtFor` resolver. `Waves`,
   `Sim`, `Meta`, `Render`, and `HeadlessMain` route through the resolver using
   `run.biome`. `"tutorial"` → `tutorialLevels`; anything else → `levels`.
5. **Tutorial flow is skippable in two places:** a `SKIP TUTORIAL` button on its
   briefing screen, and (belt-and-braces) the pause menu during a tutorial run
   shows `SKIP TUTORIAL` returning to `Phase.Menu`. `RunGen.tutorial` stays
   seed-independent; its draft pool already uses the fixed `TutorialPoolSeed`.
6. **Tutorial skips the loadout picker** (teaches building/targeting/resistances/
   popups/journal/drafting — loadout is a meta concern). Tutorial briefing BEGIN
   → `Sim.initial(run)` directly (empty loadout = unrestricted). Real runs:
   briefing BEGIN → loadout → `Sim.initial(run.copy(loadout = selected))`.
7. **The talent screen persists through `Meta.save`**, exactly like `Journal`.
   `Main` holds `MetaData` in `Phase.Talents`; a purchase calls the pure
   `Meta.purchase(data, id)`, saves on `Right`, and re-renders on `Left` (notice).
8. **Naming pass = data edit + reference update, not a code change.** Commit a
   concrete on-theme name table (below), update `config.json` (towers, enemies,
   levels, biomes rosters, twists targetTower, wave compositions) and every test
   that pins an old name. The table is tunable data; only consistency matters.
9. **Damage-kind retheme (PLAN §13) is deferred** — noted as optional step 6 of
   the task, not done by default (it ripples into `DamageKind`, resist JSON keys,
   `Messages.kind.*`, and every resist test for zero mechanical gain).

Proposed on-theme name table (data in `config.json`; adjust freely, keep ids
stable — ids are internal and NOT renamed):

| current      | proposed          | why (square/box/edge/angle)            |
|--------------|-------------------|----------------------------------------|
| Basic        | Straightedge      | kinetic, the "rule" you measure by     |
| Fire         | Matchbox          | fire in a box                          |
| Frost        | Ice Cube          | frost, cubic                           |
| Cannon       | Hole Punch        | splash = punches through a group       |
| Bombard      | Jack-in-the-Box   | explosion pops out of a box            |
| Grunt        | Cardboard         | basic, brittle box                     |
| Swift        | Paper Cut         | fast, fragile, sharp edge              |
| Brute        | Brick             | armored, slow (PLAN §7)                |
| Ember        | Cinder Block      | block + fire resist                    |
| Frostling    | Ice Tray          | frost resist, makes cubes              |
| Commander    | Pallet Jack       | speed aura (PLAN §7)                   |
| Shieldbearer | Filing Cabinet    | armor aura, office furniture           |
| Edgelord     | (keep)            | already on-theme (PLAN §7)             |
| Verdant Valley | Cardboard Lot   | level name                             |
| Ashen Ridge  | The Loading Bay   | level name                             |
| The Citadel  | Corner Office     | level name                             |

Game/window title: `"Square Defense"` (via `Messages.t("game.title")`).

Steps
-----

### Step 1 — `Model.scala`: new `Phase` cases

```scala
enum Phase:
  case Menu
  case Briefing(run: RunContext)
  case Loadout(run: RunContext, selected: Vector[String])
  case Talents(data: MetaData)
  case Playing(w: World)
  case Paused(w: World)
  case Recap(...)   // unchanged
```

`Loadout.selected` is the in-progress pick (an id-less tower-name vector, or a
`Vector[String]` of tower names). No other model changes.

### Step 2 — Config schema: `loadout` section + `tutorialLevels` array

`GameConfig.scala` — new case class + two fields:

```scala
case class LoadoutConfig(baseSlots: Int)
// GameConfig gains:
//   loadout: LoadoutConfig
//   tutorialLevels: Vector[LevelConfig]
```

`ConfigLoader.scala`:
- Parse the required scalar block: `loadout = LoadoutConfig(i(obj(root, "loadout"), "baseSlots"))`.
- Parse the array. Refactor `parseLevels` to `parseLevelsNode(node: JsonValue, known)`
  (same body, reading `node` instead of `root`), then:
  `levels = parseLevelsNode(obj(root, "levels"), known)` and
  `tutorialLevels = parseLevelsNode(obj(root, "tutorialLevels"), known)`.
  Both arrays are required and non-empty (guard both).

`Config.scala` — accessors + the level resolver (the single biome-aware switch):

```scala
def BaseLoadoutSlots: Int = current.loadout.baseSlots
def tutorialLevels: Vector[LevelConfig] = current.tutorialLevels
def levelsFor(biome: String): Vector[LevelConfig] =
  if biome == "tutorial" then current.tutorialLevels else current.levels
def levelCountFor(biome: String): Int = levelsFor(biome).size
def levelAtFor(biome: String, idx: Int): LevelConfig =
  val ls = levelsFor(biome)
  ls(math.max(0, math.min(idx, ls.size - 1)))
```

`ConfigWriter.scala` — add `"loadout"` and `"tutorialLevels"` to `root`
(`loadout = obj("baseSlots" -> num(...))`; `tutorialLevels = arr(cfg.tutorialLevels.map(level))`
— the `level` builder already exists). This keeps `ConfigWriterTest` green.

`config.json` — add after `"economy"`:
```json
"loadout": { "baseSlots": 4 },
```
and a `"tutorialLevels": [ ...2 level objects... ]` array after `"levels"`, plus a
new talent:
```json
{ "id": "loadout-slots", "name": "Corner Pocket", "layer": "Filler", "cost": 2, "prereqs": [],
  "effect": { "kind": "loadoutSlots", "params": { "slots": 1.0 } } }
```

`ConfigTest.minimalConfig` and `BranchTest.routeJson` — add both sections
(`"loadout": { "baseSlots": 4 }`, and a `tutorialLevels` array mirroring their
`levels` (wave compositions must reference only "Grunt", their only enemy)).

### Step 3 — Biome-aware level resolution (mechanical, all call sites)

- `Waves.waveCount(level)` → `Waves.waveCount(w: World)` reading
  `Config.levelAtFor(w.run.biome, w.level).waves.size`.
- `Waves.spec(level, wave)` → `Waves.spec(w: World, wave: Int)` reading
  `Config.levelAtFor(w.run.biome, w.level).waves`.
- `Sim.spawn`/`Sim.startWave`/`Sim.advanceWave` (`Sim.scala:255,293,287`) pass `w`.
- `Sim.completeLevel` (`Sim.scala:313`): `Config.levelCount` → `Config.levelCountFor(w.run.biome)`.
- `Meta.payout` (`Meta.scala:75`): `Config.levelCount` → `Config.levelCountFor(w.run.biome)`.
- `Render.drawHud` (`Render.scala:449,468-469`): use `levelCountFor`/`levelAtFor`/`Waves.*(w)`.
- `HeadlessMain.summary` (`HeadlessMain.scala:86`): use `levelCountFor(w.run.biome)`.

`Config.levelCount`/`Config.levelAt` remain (the editor and `Config.levelAt` uses
in `BranchTest` keep working for the real-level table); the sim/render/payout
path all goes through the resolver.

### Step 4 — Loadout gating + loadout-aware HUD

`Sim.canBuild` (`Sim.scala:93-97`):
```scala
def canBuild(w: World, cell: GridPos, tpe: String): Boolean =
  val spec = Config.towerSpec(tpe)
  (w.run.loadout.isEmpty || w.run.loadout.contains(tpe)) &&
    w.gold >= spec.cost &&
    Grid.isBuildable(cell, w.level) &&
    !w.towers.exists(_.cell == cell)
```

`Main.scala` — in the `Playing` (non-draft) branch, the tower list the player can
pick from is the loadout when non-empty:
```scala
val pool = if w.run.loadout.isEmpty then Config.game.towers else
  Config.game.towers.filter(t => w.run.loadout.contains(t.name))
```
`selected` clamps to `pool.size - 1`; `Render.drawHud` and `drawGame` take the
same `pool` (or the run's loadout) so the `[1..N]` build list and the number keys
agree with `canBuild`. `Bot` needs no change (empty loadout ⇒ unrestricted).

### Step 5 — `Main.scala`: screen routing

- `Phase.Menu`: three buttons — `PLAY`, `TUTORIAL`, `TALENTS` — plus keep the
  hidden `T` hotkey for tutorial. `PLAY` → `Phase.Briefing(RunGen.roll(...))`;
  `TUTORIAL` → `Phase.Briefing(RunGen.tutorial(...))`; `TALENTS` →
  `Phase.Talents(Meta.active())`.
- `Phase.Briefing(run)`: `BEGIN` → if `run.biome == "tutorial"` then
  `Phase.Playing(Sim.initial(run))` else `Phase.Loadout(run, Vector.empty)`.
  `SKIP TUTORIAL` (tutorial only) → `Phase.Menu`.
- `Phase.Loadout(run, selected)`: click a tower row to toggle it into/out of
  `selected` (bounded by `Config.BaseLoadoutSlots + Meta.effects(run.talents).loadoutSlots`);
  `CONFIRM` → `Sim.initial(run.copy(loadout = selected))` → `Phase.Playing`;
  `BACK` → `Phase.Briefing(run)`.
- `Phase.Talents(data)`: click an affordable, prereq-satisfied, un-owned talent →
  `Meta.purchase(data, id)`; on `Right(next)` `Meta.save(next)` and continue as
  `Phase.Talents(next)`; on `Left(err)` show `notice`. `BACK` → `Phase.Menu`.
- `beginRun`/`toRecap` unchanged (already bank + snapshot journal).

### Step 6 — `Render.scala` + `Ui`: three new screens

`Ui` (in `Config.scala`) — new button/row rects alongside `Play`/`Retry`/`Menu`:
`Begin`, `SkipTutorial`, `ConfirmLoadout`, `Back`, `Talents`, `TowerRow(i)`,
`TalentRow(i)`. Reuse the existing `Rect`/`button`/`buttonLabel`/`centered`/`text`
helpers; all colors from `Config.*Color`.

- `drawBriefing(run: RunContext, isTutorial: Boolean)` — title, biome name,
  environment rule (`env.<kind>` message for `environment.kind`, `briefing.envNone`
  when `None`), resist theme (`briefing.resistPrimary`/`Secondary` using
  `kindName(primary/secondary)` — telegraphed kinds only, never amounts), twists
  (`briefing.twist` + `twist.<id>`), and `BEGIN` (+ `SKIP TUTORIAL`).
- `drawLoadout(run: RunContext, selected: Vector[String], slots: Int)` — one row
  per tower (name, cost, kind, selected marker), a `slots used / total` line, and
  `CONFIRM`/`BACK`. Reject over-picking with a notice.
- `drawTalents(data: MetaData)` — balance line, then fillers / cores / deeps
  grouped by `TalentLayer` with owned/locked(prereq)/affordable states and costs;
  a footer summarizing `Meta.effects(data.talents)` (damage/range/gold mults,
  active cores, loadout slots, reroll). `BACK`.

### Step 7 — Tutorial content (2 fixed levels, data) + skippability

- Author `tutorialLevels` in `config.json` (2 levels, simple routes, a few waves,
  rosters drawn from the tutorial biome's enemies). Level 1 must include at least
  one enemy with a nonzero base resist (so a RESIST popup + journal entry actually
  occur) and cover building/targeting; level 2 covers resistance + drafting (the
  between-level draft fires automatically). Content is data — no code.
- Tutorial is skippable via `SKIP TUTORIAL` on its briefing and via a
  `SKIP TUTORIAL` entry in `drawPaused` when `w.run.biome == "tutorial"`.

### Step 8 — i18n message keys (`messages_en.properties`)

Add (ASCII only), localizing exactly what this task introduces:

```
game.title=Square Defense
briefing.title=BRIEFING
briefing.biome=biome
briefing.environment=environment
briefing.envNone=(none)
briefing.resistTheme=resist theme
briefing.resistPrimary=primary
briefing.resistSecondary=secondary
briefing.twists=twists
briefing.twistsNone=none
briefing.begin=BEGIN
briefing.skipTutorial=SKIP TUTORIAL
env.openFloorPlan=enemies gain armor while near each other
env.crushed=dying heavies leave a slowing wreck
env.recycling=leaks are cheaper, but swarms are larger
loadout.title=CHOOSE YOUR LOADOUT
loadout.slots=slots %d / %d
loadout.confirm=CONFIRM
loadout.back=BACK
loadout.full=loadout is full
talents.title=TALENTS
talents.balance=currency %d
talents.back=BACK
talents.owned=OWNED
talents.locked=needs %s
talents.effects=active effects
talents.damageMult=damage %s
talents.rangeMult=range %s
talents.goldMult=gold %s
talents.loadoutSlots=loadout slots +%d
talents.cores=cores %s
```

(If `showCodes` is on, these render as keys, which is fine.)

### Step 9 — Naming + color pass

- Edit `config.json`: rename towers/enemies/levels per the table; update
  `twist.targetTower`, every biome `roster`, and every wave `composition`
  (levels + tutorialLevels) to the new enemy names. **Do not rename ids**
  (`tutorial`, `cube-farm`, `twist`/`talent`/`relic`/`augment` ids, resist JSON
  keys) — only player-facing `name`/`targetTower`/`roster`/`composition` strings.
- Update `Main.scala:9` window title → `Messages.t("game.title")`; `Render.scala:188`
  menu title → `Messages.t("game.title")`.
- Update the tests that pin old names (`MetaTest`, `TwistTest`, `DraftTest`,
  `EnvRulesTest`, `BranchTest`, `JournalTest`, `ConfigCodecTest`, `ConfigTest`
  fixtures) to the new names. Mechanical `replaceAll` per file.
- Color pass: no schema change — confirm the new screens reuse `Config.*Color`
  and that the HUD uses `Config.DimText` vs `Config.TextColor` consistently.
  Re-run `ConfigWriterTest` (round-trip) to prove config/data edit is lossless.

### Step 10 — Tests

New `core/src/test/scala/td/LoadoutTest.scala` (headless):
1. `canBuild` allows a tower in the loadout and rejects one not in it.
2. Empty loadout is unrestricted (back-compat guard for `Bot`/old runs).
3. `Config.BaseLoadoutSlots` loads (pin the data contract, like `ConfigTest`).
4. `loadout-slots` talent increases `Meta.effects(...).loadoutSlots`.
5. `RunGen.roll`/`tutorial` default `loadout` to empty (unrestricted).
6. Determinism re-assert: same seed → identical `RunContext` (unchanged fields).

New/updated config tests:
- `ConfigTest`: `minimalConfig` gains `loadout` + `tutorialLevels`; assert
  `tutorialLevels` loads (size 2) and `levelsFor("tutorial") != levelsFor("cube-farm")`.
- `BranchTest.routeJson`: add the two new sections.
- `MetaTest.payout`: change `Config.levelCount` → `Config.levelCountFor("tutorial")`
  in the `won` case (tutorial now has 2 levels, not 3).
- `EnvRulesTest.recycling: swarms are larger` (`EnvRulesTest.scala:87-93`): the
  `tutorial` wave-1 count assertion (currently `8`) must match the new
  tutorial-level-0 wave-1 composition total.

Files
-----
- `core/src/main/scala/td/Model.scala` — new `Phase` cases.
- `core/src/main/scala/td/GameConfig.scala` — `LoadoutConfig` + `GameConfig.loadout`/`tutorialLevels`.
- `core/src/main/scala/td/ConfigLoader.scala` — parse `loadout` + `tutorialLevels`.
- `core/src/main/scala/td/ConfigWriter.scala` — serialize `loadout` + `tutorialLevels`.
- `core/src/main/scala/td/Config.scala` — `BaseLoadoutSlots`, `levelsFor/levelCountFor/levelAtFor`, new `Ui` rects.
- `core/src/main/resources/config.json` — `loadout` section, `tutorialLevels`, `loadout-slots` talent, rename table.
- `core/src/main/resources/messages/messages_en.properties` — new keys.
- `core/src/main/scala/td/Waves.scala` — biome-aware `waveCount`/`spec`.
- `core/src/main/scala/td/Sim.scala` — loadout gate in `canBuild`; resolver call sites.
- `core/src/main/scala/td/Meta.scala` — `payout` resolver call site.
- `headless/src/main/scala/td/HeadlessMain.scala` — `summary` resolver call site.
- `game/src/main/scala/td/Main.scala` — phase routing + loadout-aware selection.
- `game/src/main/scala/td/Render.scala` — `drawBriefing`/`drawLoadout`/`drawTalents` + pause skip + HUD pool.
- Tests: `core/src/test/scala/td/LoadoutTest.scala` (new); updated `ConfigTest`, `BranchTest`, `MetaTest`, `EnvRulesTest`, `TwistTest`, `DraftTest`, `JournalTest`, `ConfigCodecTest`, `ConfigWriterTest`.

Verify
------
1. `sbt compile`.
2. `sbt test` — full suite incl. new `LoadoutTest` and the config/test-fixture updates.
3. Visual (needs display): `sbt "game/run"` — menu shows PLAY/TUTORIAL/TALENTS;
   PLAY → briefing (biome, env rule, resist theme, twist — no exact resist %s) →
   loadout (slot cap enforced) → play; TUTORIAL → briefing with SKIP → 2 levels →
   recap; TALENTS → spend currency, prereq gating, active-effects summary.
4. Tutorial is identical every run (same seed content) and skippable from briefing
   and pause.
5. Briefing never reveals discoverable exacts (only kinds, never amounts; only
   telegraphed rule text).

Notes / Open questions
----------------------
1. `loadout`/`tutorialLevels` are required sections, so the two inline fixtures
   (`ConfigTest.minimalConfig`, `BranchTest.routeJson`) MUST gain them or their
   negative-parse tests break — the same pattern as task09's `meta` section.
2. Empty-loadout = unrestricted is a back-compat convenience for `Bot`/tests; the
   GUI always writes a concrete loadout for real runs, so players still get the
   restriction. If a stricter invariant is wanted later, move the "always set a
   loadout" default into `RunGen.roll` and update `Bot` to respect it.
3. The tutorial's exact 2-level content (routes/waves) is content data; keep at
   least one base-resist enemy in level 1 or the "teaches resistances" goal is
   unmet. The draft between tutorial levels teaches drafting automatically.
4. The proposed name table is tunable data — the binding constraint is that the
   names are on-theme and every cross-reference (`targetTower`, `roster`,
   `composition`, tests) is updated in the same pass. `ConfigWriterTest`'s
   round-trip guards that the config edit is lossless.
5. Defer the PLAN §13 damage-kind retheme (Kinetic→Momentum etc.) — it touches
   `DamageKind`, the `resist` JSON keys, `Messages.kind.*`, and ~every resist test
   for purely cosmetic gain.

Implementation gotchas (for the coding agent)
---------------------------------------------
- **Rename only `name`/`targetTower`/`roster`/`composition`, never ids.** ids are
  the stable keys (`tutorial`, `cube-farm`, `pyromancer`, `square-deal`, ...);
  renaming them breaks talent prereqs, relic gating, and the journal.
- **Update `ConfigWriter` in the same change as `GameConfig`** — `ConfigWriterTest`
  round-trips `Config.game` and will fail if `root` doesn't emit the new fields.
- **`Waves.spec`/`waveCount` now take a `World`** — grep every call site (Sim,
  Render, tests) or the signature change leaves a stale `(level, wave)` call.
- **`MetaTest.payout`'s `won` case** currently asserts against `Config.levelCount`
  (3); after the resolver, a won tutorial run pays out on `levelCountFor("tutorial")`
  (2) — update both the world construction and the expected value together.
- **`EnvRulesTest` "swarms are larger"** asserts the tutorial's wave-1 spawn count
  (`8`); pick a tutorial-level-0 wave-1 total and keep the test in sync, or that
  test fails on the new content.
- **`loadoutSlots` params are `Float`** — the talent's `slots` must be `1.0`, and
  `Meta.effects` already truncates with `.toInt` (`Meta.scala:103`), so keep the
  value integral in the JSON.
- **`Phase` gains data-carrying cases** — the `Main.render` match must handle all
  three new cases exhaustively (Scala 3 non-exhaustive match is a warning/error).
- **`Ui` rects read `Config.WorldW` at call time** — new row rects must be `def`s
  (like the existing `Play`/`DraftOffer`), not `val`s, so they track a config reload.
- **Keep the hidden `T` tutorial hotkey** (`Main.scala:54`) or the "T to start
  tutorial" affordance disappears with the new TUTORIAL button; keep both.
