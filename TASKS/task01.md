TASK 01 — RESTRUCTURE BUILD INTO SUBPROJECTS
=============================================

Goal
----
Split the single sbt module into `core`, `game`, `editor`, `headless`
subprojects while keeping everything compiling. No new gameplay — just a
faithful move so later tasks have the right home.

Depends on
----------
None (this is the first task).

Plan refs
---------
PLAN section 3 (tooling) and context.md section 2 (target layout).

Steps
-----
1. Rewrite build.sbt:
   - `lazy val core` — source in `core/src/main/scala`, resources
     `core/src/main/resources` (move config.json here). libraryDependencies:
     gdx core only, plus `"org.scalameta" %% "munit" % "1.0.4" % Test` so
     `sbt test` runs. (munit is a library, not a plugin — no sbt plugin added.)
   - `lazy val game` — dependsOn(core); gdx-backend-lwjgl3 + gdx-platform
     natives-desktop; `fork := true`.
   - `lazy val editor` — dependsOn(core).
   - `lazy val headless` — dependsOn(core).
   - `lazy val root` — aggregate(core, game, editor, headless).
2. Move source files:
   - core: Sim, Model, Config, GameConfig, ConfigLoader, Grid, Waves.
   - game: Main (TdGame), Render.
   - editor/headless: created empty in later tasks (stub packages now so the
     aggregate resolves, e.g. a `package object` or a placeholder object).
3. Move `src/main/resources/config.json` -> `core/src/main/resources/config.json`.
   ConfigLoader.readSource loads it from the classpath, so no code change.
4. Keep package `td` everywhere (no package renames).

Files
-----
- build.sbt (rewrite)
- core/src/main/scala/td/{Sim,Model,Config,GameConfig,ConfigLoader,Grid,Waves}.scala
- game/src/main/scala/td/{Main,Render}.scala
- core/src/main/resources/config.json
- delete old src/ tree once moved.

Verify
------
- `sbt compile` succeeds from repo root.
- `sbt test` succeeds (munit wired; a trivial smoke test is fine).
- `sbt "game/run"` still launches the window (needs a display).
- Config loads unchanged (game starts with the same board/waves).

Notes
-----
- Preserve `fork := true` ONLY on the game project (AGENTS.md gotcha).
- Don't add new features here; resist the urge to "fix" anything while moving.
