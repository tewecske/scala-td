ROGUELITE TOWER DEFENSE — TASK CONTEXT
=======================================

Read this before starting ANY task. The design lives in ../PLAN.txt (the
"PLAN" referenced throughout). AGENTS.md describes the current single-module
codebase; task01 restructures it into subprojects.

1. THE GAME
-----------
Scala 3 tower-defense on libGDX, becoming a roguelite with a knowledge loop.
Vision (PLAN section 1): every run banks partial progress, teaches the player
something, and advances permanent meta progression; each run rolls a themed
"biome" that reshapes enemy roster, resist profile, and a global environment
rule.

Locked decisions (PLAN section 2) — these are NOT up for debate in a task:
- Tutorial biome: 2 fixed levels, always identical, skippable.
- Real game starts with 3 biomes.
- ONE shared talent tree with mid-tree "cores" (archetypes) that can be MIXED
  at end game (not exclusive).
- Knowledge: first encounter VAGUE; after N hits the EXACT value is recorded.
- Live discovery (resist popups on hit, first-meet name flash).
- Journal banks INSTANTLY, never gated.
- No rerolls at launch; rerolls unlocked via talent, cost IN-RUN GOLD.
- One augment socket per tower.
- Branches exist; enemies traverse randomly; side-taking specialists later.
- EVERY tunable value comes from a config file (data-driven, no hardcoding).
- A separate config editor app provides a UI to edit configuration.
- A headless mode runs simulations with a custom setup.

2. TARGET REPO LAYOUT (after task01)
------------------------------------
Separate sbt subprojects, all still package `td`:
  core/     — all rules + data. Sim, Model, Config, GameConfig, ConfigLoader,
              Grid, Waves, Rng, RunGen, Biomes, Journal, Meta, Talent, Relic,
              Augment, Twist. Resources: config.json, messages/messages_*.properties
              (and later talent/relic data). Depends on libGDX CORE only (Color, JsonReader are
              headless-safe; no natives needed).
  game/     — Main (TdGame) + Render + bloom shaders. Depends on core +
              gdx-backend-lwjgl3 + gdx-platform natives-desktop.
  editor/   — ConfigEditorApp. Depends on core. UI = Swing (JDK built-in, no
              new deps); the editable/validation logic lives in core.
  headless/ — HeadlessMain (CLI). Depends on core only.

build.sbt grows a root aggregate with `core`, `game`, `editor`, `headless`
projects. `fork := true` stays on the game project (LWJGL3 needs its own JVM).

3. ARCHITECTURE PRINCIPLES (do not violate)
-------------------------------------------
- Sim.step(world, dt, cmds) is a PURE function of (state, time, input); it
  never touches libGDX/rendering. All new roguelite logic that changes the
  world goes here and stays pure + headlessly testable.
- State is immutable case classes (Model.scala). Player intent = Command enum
  (Model.scala); screens = Phase enum. Add new cases rather than globals.
- ALL randomness is rolled ONCE at run start (seeded RNG); Sim.step only READS
  the rolled values. Never consume RNG inside a step.
- canBuild (Sim.scala) is the SINGLE build-validity check shared by renderer
  preview and the actual rule. Loadout gating, debuff pricing, and augment
  logic must all funnel through it.
- Config is the single source of truth for tuning (Config.scala defs resolve
  against Config.game). New tuning values extend the JSON schema + loader.
- Enemy route is authored as corner waypoints in Grid; path cells and the
  walkable polyline are DERIVED. Edit waypoints, not derived data.

4. KEY FILES (current, pre-task01)
----------------------------------
- Model.scala      — World, Enemy, Tower, Projectile, Resistances, DamageKind,
                     Aura, Command, Phase.
- Sim.scala        — Sim.step and all rules.
- Config.scala     — accessors resolving against the active GameConfig.
- GameConfig.scala — TowerSpec, EnemySpec, WaveSpec, LevelConfig, GameConfig...
- ConfigLoader.scala — JSON -> GameConfig (libGDX JsonReader, headless-safe).
- Messages.scala    — i18n accessor (code -> text); see section 9.
- Grid.scala       — board, route derivation, positionAt, isBuildable.
- Waves.scala      — wave pacing lookup + spawnOrder.
- Main.scala       — TdGame glue (input/screens).
- Render.scala     — ShapeRenderer + built-in BitmapFont, bloom shaders.
- src/main/resources/config.json — the bundled tuning.

5. BUILD & TESTING (applies to EVERY task)
------------------------------------------
Build:
- `sbt compile` — builds (repo root).
- `sbt test` — runs the munit unit tests (core logic; headless-safe, no GL).
- `sbt "game/run"` — GUI window (needs a display).
- `sbt "headless/run --seed N"` — headless sim (task04), NOT the GUI.
- Scala 3 indentation syntax (no braces) throughout.

Testing convention (needed for all tasks, so it lives here):
- Deterministic core-logic changes SHIP a munit test under
  core/src/test/scala/td/. Sim is pure, so step a seeded World and assert on
  the resulting state — no window, no timing, no flakiness.
- Always assert determinism: same seed -> identical World after N steps.
- Re-assert the single-source-of-truth invariants (canBuild, resist layering,
  loadout gating) whenever a task touches them.
- munit is a Test-scope library dependency, NOT an sbt plugin; task01 wires it
  into build.sbt and `sbt test` auto-detects it.
- GUI-only changes are verified manually via `sbt "game/run"` (task Verify
  sections say so). No scalafmt, no CI.

6. CONVENTIONS
--------------
- DO NOT add code comments unless asked.
- Follow existing code style (Scala 3, package `td`, immutability).
- Data before code: new content (biomes, talents, relics, augments, twists,
  enemy/tower specs) is JSON config, not hardcoded tables.
- All user-facing strings go through `Messages.t(code)`, never a raw literal
  (section 9).
- Save data (meta currency, talent unlocks, journal) is JSON via the existing
  JsonReader/JsonValue pattern (ConfigLoader is the reference implementation).

7. TASK FILE FORMAT
-------------------
Each taskNN.md has: Goal, Depends on, Plan refs, Steps, Files, Verify, and
Notes/Open decisions. Start at task01 (or the first unstarted task whose
dependencies are all done). A task is DONE only when its Verify section passes
(which, for any task that ships logic, includes passing `sbt test` with the
munit tests it added per section 5).

8. NAMING THEME (kept uniform across all content)
-------------------------------------------------
Square/box/cube/edge/angle/line vocabulary. Square = natural order; non-square
polygons = eldritch (elites/bosses). Examples are in PLAN section 7 — reuse
them verbatim unless a task explicitly renames. Keep names funny.

9. INTERNATIONALIZATION (I18N)
------------------------------
- All user-facing text (menus, buttons, HUD labels, notices, window title)
  resolves through `Messages.t("code")` — never a raw string literal.
- Translations live in Java `.properties` files under
  `core/src/main/resources/messages/messages_<lang>.properties`, one
  `code=text` line per string.
- `Messages.t(code, args*)` supports printf-style interpolation (`%s`, `%d`).
  A missing code falls back to the code itself, so an untranslated key is
  visible rather than crashing.
- The active language comes from config only (`config.json` -> `i18n.lang`);
  no in-game language picker yet.
- Debug "code mode": `Messages.showCodes` starts from config
  (`i18n.showCodes`) and is toggled at runtime with Alt+I. When on, `t`
  returns the KEY instead of the text, so devs/translators can see which code
  backs each label. It is a rendering-only concern — it never touches `Sim`.
- Each task localizes the strings IT defines: add keys to
  `messages_en.properties` (and other languages) as part of the task that
  introduces the text; do not do one big retroactive sweep.
- GOTCHA: libGDX's built-in BitmapFont has a limited glyph set (the game uses
  no font assets). Translations must stay ASCII-safe for now; non-ASCII scripts
  (accented Latin, Cyrillic, CJK) will not render until a font asset is added.
- Content names (tower/enemy/level names) come from config.json and are NOT
  routed through Messages (they are data, not UI).
