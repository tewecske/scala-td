# AGENTS.md

Scala 3 tower-defense game on libGDX (LWJGL3 desktop backend). Multi-project sbt build (`core`/`game`/`editor`/`headless`), package `td`.

## Commands

- `sbt compile` — build
- `sbt "game/run"` — launches the GUI window; needs a display, so not runnable headless
- `sbt test` — munit unit tests (headless-safe). Tests are wired in by `TASKS/task01`; see `TASKS/context.md` section 5 for the testing convention. No scalafmt, no CI.

## Architecture

- All game rules live in `Sim` (`src/main/scala/td/Sim.scala`). `Sim.step(world, dt, cmds)` is a pure function of (state, time, input) and never touches libGDX — test it headlessly.
- State is an immutable `World` case class (`Model.scala`); player intent is the `Command` enum, screens are the `Phase` enum.
- `Main.scala` (`TdGame`) only wires input/screen selection; `Render.scala` only draws. Shapes via ShapeRenderer; text via TrueType fonts (`game/src/main/resources/fonts`, OFL-licensed Orbitron + Inter) rasterized by gdx-freetype at the window's pixel size (`Fonts.scala`). No other assets.
- Screen layout lives in `Layout.scala` (game): every rectangle in UI units on a canvas of at least 1920x1080 that extends with the window's aspect ratio. Renderer and click handling share those rectangles. The board keeps one tile size on every map (the largest map just fits) and is centered; it is drawn in simulation coordinates through a transform (`Layout.toUi` / `Layout.toSim`), so changing the look never changes the sim.
- Display settings (windowed / fullscreen / borderless, resolution) are saved in `data/settings.json` (`DisplaySettings`, core) and applied by `Display.scala` (game).
- Screenshots: F12 in game saves to `data/screenshots`. `TD_TOUR=<dir> sbt game/run` visits every screen, saves a PNG of each and exits (`Tour.scala`); `TD_WINDOW=WxH` forces the window size.
- Tuning constants are centralized: `Config.scala` (board/economy/combat), `Waves.scala` (difficulty curve), `Grid.scala` (route + path derivation).

## Gotchas

- `fork := true` in build.sbt is required — LWJGL3 must run in its own JVM. Don't remove it.
- Scala 3 indentation syntax (no braces) throughout.
- The enemy route is authored as corner waypoints in `Grid.waypoints`; path cells and the walkable polyline are derived from them — edit waypoints, not derived data.
- Fast-forward replays extra whole `Sim.step` calls rather than scaling `dt`, so rules behave identically at any speed. Preserve this.
- `dt` is clamped to `Config.MaxStepDt` (0.05) so enemies can't tunnel past towers after a stall.
- `Sim.canBuild` is the single build-validity check shared by the renderer preview and the actual rule — keep them in sync.
