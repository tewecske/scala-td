# AGENTS.md

Scala 3 tower-defense game on libGDX (LWJGL3 desktop backend). Single sbt module, package `td`.

## Commands

- `sbt compile` — build
- `sbt run` — launches the GUI window; needs a display, so not runnable headless
- No tests, no scalafmt, no sbt plugins, no CI. `sbt test` has nothing to run.

## Architecture

- All game rules live in `Sim` (`src/main/scala/td/Sim.scala`). `Sim.step(world, dt, cmds)` is a pure function of (state, time, input) and never touches libGDX — test it headlessly.
- State is an immutable `World` case class (`Model.scala`); player intent is the `Command` enum, screens are the `Phase` enum.
- `Main.scala` (`TdGame`) only wires input/screen selection; `Render.scala` only draws. Renderer uses ShapeRenderer + the built-in BitmapFont — no asset files, no asset pipeline.
- Tuning constants are centralized: `Config.scala` (board/economy/combat), `Waves.scala` (difficulty curve), `Grid.scala` (route + path derivation).

## Gotchas

- `fork := true` in build.sbt is required — LWJGL3 must run in its own JVM. Don't remove it.
- Scala 3 indentation syntax (no braces) throughout.
- The enemy route is authored as corner waypoints in `Grid.waypoints`; path cells and the walkable polyline are derived from them — edit waypoints, not derived data.
- Fast-forward replays extra whole `Sim.step` calls rather than scaling `dt`, so rules behave identically at any speed. Preserve this.
- `dt` is clamped to `Config.MaxStepDt` (0.05) so enemies can't tunnel past towers after a stall.
- `Sim.canBuild` is the single build-validity check shared by the renderer preview and the actual rule — keep them in sync.
