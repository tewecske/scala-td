TASK 04 — PLAN (Headless Runner + CLI)
======================================

Research
--------
Verified against the current codebase:

- **`headless` subproject** (`build.sbt:37-40`) already `.dependsOn(core)`. It has
  NO `mainClass` and NO `fork`. The only source file is a stub
  `headless/src/main/scala/td/HeadlessMain.scala` (just `object HeadlessMain`,
  no `main`), so `sbt "headless/run"` currently fails with "no main class".
- **Level advancement lives INSIDE `Sim.step`**, not in `Main`: `Sim.step` →
  `advanceWave` (`Sim.scala:91`) → `startWave`/`completeLevel`
  (`Sim.scala:101/115`). `completeLevel` increments `world.level`, clears
  towers/enemies/shots, adds `Config.LevelBonus`, and sets `won = true` (and
  freezes) when `next >= Config.levelCount`. `Main` only switches screens on
  `next.won`/`next.isOver`. So stepping `Sim` with empty (or bot) cmds DOES
  advance waves and levels to completion.
- `Sim.step` short-circuits on `w0.isOver || w0.won` (`Sim.scala:28`), so a
  frozen world never advances — the loop terminates.
- `World` (`Model.scala:100`) has fields `level, towers, enemies, shots, gold,
  lives, wave, spawnQueue, spawnTimer, breakTimer, nextId, run, won` and does
  NOT track kills or leaks. `Phase.GameOver(level, wave)` / `Phase.Victory(level,
  lives, gold)` already carry the summary numbers.
- `World` has one construction site (`Sim.initial`) and all `World.copy(...)`
  calls pass named fields, so adding defaulted `kills`/`leaks` flows through
  with zero edits. No test constructs `World` positionally.
- Kill/leak increment sites in `Sim` (three disjoint per-step subsets, no
  double-count):
  - `tickEffects` (`Sim.scala:180-182`): burn deaths → `dead.size` kills.
  - `moveEnemies` (`Sim.scala:186-188`): `leaked.size` leaks.
  - `moveProjectiles` (`Sim.scala:289-295`): shot deaths → `dead.size` kills.
  `completeLevel` only clears when `spawnQueue.isEmpty && enemies.isEmpty`, so
  it never eats uncounted enemies.
- Config plumbing: `Config.load(path): Boolean` (keep-on-failure),
  `ConfigLoader.load(path): Either[String, GameConfig]` (detailed `Left(err)`),
  `Config.MaxStepDt = 0.05`, `Config.game`, `Config.levelCount`,
  `Config.StartLives`, `Config.KillReward`.
- `Sim.initial(seed)` sets `run = RunContext(seed = seed)`, `biome = "tutorial"`.
  `RunContext.DefaultSeed = 0x5EEDL` (= 24301). Spawning is deterministic from
  `Waves.spawnOrder`; the seed is not consumed inside `step` yet (no RNG read),
  so same seed+config ⇒ bit-identical; different seeds differ only in the echoed
  `seed` field until Phase 1 uses `Rng`.
- Bot symbols verified: `Sim.canBuild(w, cell, tpe)` (`Sim.scala:54`),
  `Grid.Size` (`Grid.scala:69`), `Grid.isBuildable(p, level)` (`Grid.scala:87`),
  `GridPos(x, y)`.

Decisions (confirmed)
---------------------
1. **Kills/leaks = core change.** Add `kills: Int = 0` and `leaks: Int = 0` to
   `World`, incremented in `Sim.step`. Simplest and exact; keeps `Sim` the single
   source of truth; design needs these stats later. "Levels cleared"/"waves
   reached" need no new fields (derived from `level`/`wave`/`won`).
2. **Summary line**: single tab-separated `key=value` line on **stdout**; all
   diagnostics/errors on **stderr**.
3. **`dt = Config.MaxStepDt`** (fixed), mirroring `Main`'s clamp.
4. **Autoplay bot ON by default**, disabled via `--no-bot`. Lives only in
   `headless`, never in `core`/`Sim`. Policy: build the cheapest affordable tower
   at the first buildable cell (row-major scan).
5. **Exit codes**: `0` success + `--help`; `1` config not-found/parse; `2` bad
   args. Requires `fork := true` so `sys.exit` kills only the forked JVM.
6. **`Compile / mainClass := Some("td.HeadlessMain")`** (the stub has no `main`,
   so auto-detection fails).
7. **End state is a 3-way enum**: `WON` (`world.won`), `LOST` (`world.isOver`),
   `STOPPED` (a `--max-levels`/`--max-steps` cap hit) — keeps the cap honest.

Steps
-----

### Step 1 — `core`: track kills & leaks in `World` + `Sim`

`Model.scala` — add two defaulted fields after `won`:

```scala
case class World(
    level: Int,
    towers: Vector[Tower],
    enemies: Vector[Enemy],
    shots: Vector[Projectile],
    gold: Int,
    lives: Int,
    wave: Int,
    spawnQueue: Vector[String],
    spawnTimer: Float,
    breakTimer: Float,
    nextId: Long,
    run: RunContext,
    won: Boolean = false,
    kills: Int = 0,
    leaks: Int = 0
):
  def isOver: Boolean   = lives <= 0
  def inBreak: Boolean  = breakTimer > 0f
```

`Sim.scala` — three one-line changes (copies already named-field):

`tickEffects`:
```scala
      if dead.isEmpty then w.copy(enemies = alive)
      else w.copy(enemies = alive, gold = w.gold + dead.size * Config.KillReward, kills = w.kills + dead.size)
```

`moveEnemies`:
```scala
    if leaked.isEmpty then w.copy(enemies = still)
    else w.copy(enemies = still, lives = math.max(0, w.lives - leaked.size), leaks = w.leaks + leaked.size)
```

`moveProjectiles`:
```scala
    w.copy(
      shots = fs.result(),
      enemies = alive,
      gold = w.gold + dead.size * Config.KillReward,
      kills = w.kills + dead.size
    )
```

No change to `completeLevel`.

### Step 2 — `core` test: kills/leaks counters

Add to `core/src/test/scala/td/SimSmokeTest.scala` (or a new `KillLeakTest.scala`):

```scala
  test("initial world has zero kills and leaks") {
    val w = Sim.initial(RunContext.DefaultSeed)
    assertEquals(w.kills, 0)
    assertEquals(w.leaks, 0)
  }

  test("leaks accumulate with no towers; kills stay zero") {
    var w = Sim.initial(1L)
    var guard = 0
    while w.leaks == 0 && guard < 100000 do
      w = Sim.step(w, Config.MaxStepDt, Vector.empty)
      guard += 1
    assert(w.leaks > 0)
    assertEquals(w.kills, 0)
    assert(w.lives < Config.StartLives)
  }
```

### Step 3 — `headless` bot

New `headless/src/main/scala/td/Bot.scala` (throwaway, headless-only):

```scala
package td

/** Minimal autoplay so a headless sim isn't empty. Not part of the real game's
  * input path — it only fabricates [[Command.Build]] for [[Sim.step]].
  */
object Bot:

  def step(world: World): Vector[Command] =
    val cheapest = Config.game.towers.minBy(_.cost)
    if world.gold < cheapest.cost then Vector.empty
    else firstBuildable(world, cheapest.name) match
      case Some(cell) => Vector(Command.Build(cell, cheapest.name))
      case None       => Vector.empty

  private def firstBuildable(world: World, tpe: String): Option[GridPos] =
    val size = Grid.Size
    (0 until size).iterator
      .flatMap(y => (0 until size).iterator.map(GridPos(_, y)))
      .find(Sim.canBuild(world, _, tpe))
```

`Sim.canBuild` already rejects occupied/path/non-buildable/unaffordable cells, so
the bot can't double-build. `find` (early-exit), not `for…yield`.

### Step 4 — `headless` CLI (replace the stub)

```scala
package td

object HeadlessMain:

  private val DefaultMaxSteps = 1000000L

  private case class Options(
      seed: Long = RunContext.DefaultSeed,
      config: Option[String] = None,
      maxLevels: Int = Int.MaxValue,
      maxSteps: Long = DefaultMaxSteps,
      bot: Boolean = true
  )

  private val usage =
    """usage: headless [options]
      |  --seed <long>        run seed (default 24301)
      |  --config <path>      load config from JSON file (default bundled)
      |  --max-levels <n>     stop after reaching level index n (0-based)
      |  --max-steps <n>      hard cap on Sim.step calls (default 1000000)
      |  --no-bot             don't run the autoplay build bot
      |  --help               print this help and exit""".stripMargin

  def main(args: Array[String]): Unit =
    if args.contains("--help") || args.contains("-h") then
      println(usage)
      sys.exit(0)
    parseArgs(args.toList) match
      case Left(err) =>
        System.err.println(s"error: $err")
        System.err.println(usage)
        sys.exit(2)
      case Right(opts) => run(opts)

  private def parseArgs(args: List[String]): Either[String, Options] =
    def loop(rest: List[String], acc: Options): Either[String, Options] =
      rest match
        case Nil => Right(acc)
        case "--seed" :: v :: t =>
          v.toLongOption match
            case Some(s) => loop(t, acc.copy(seed = s))
            case None    => Left(s"invalid --seed value '$v'")
        case "--seed" :: Nil => Left("--seed requires a value")
        case "--config" :: v :: t => loop(t, acc.copy(config = Some(v)))
        case "--config" :: Nil     => Left("--config requires a value")
        case "--max-levels" :: v :: t =>
          v.toIntOption.filter(_ > 0) match
            case Some(n) => loop(t, acc.copy(maxLevels = n))
            case None    => Left(s"invalid --max-levels value '$v'")
        case "--max-levels" :: Nil => Left("--max-levels requires a value")
        case "--max-steps" :: v :: t =>
          v.toLongOption.filter(_ > 0) match
            case Some(n) => loop(t, acc.copy(maxSteps = n))
            case None    => Left(s"invalid --max-steps value '$v'")
        case "--max-steps" :: Nil => Left("--max-steps requires a value")
        case "--no-bot" :: t => loop(t, acc.copy(bot = false))
        case other :: _ => Left(s"unknown argument '$other'")
    loop(args, Options())

  private def run(opts: Options): Unit =
    opts.config.foreach { path =>
      ConfigLoader.load(path) match
        case Left(err) =>
          System.err.println(s"config error: $err")
          sys.exit(1)
        case Right(_) =>
          Config.load(path) // known-good now; applies the config
    }

    val dt    = Config.MaxStepDt
    var world = Sim.initial(opts.seed)
    var steps = 0L
    var done  = false
    while !done do
      val cmds = if opts.bot then Bot.step(world) else Vector.empty
      world = Sim.step(world, dt, cmds)
      steps += 1
      done = world.won || world.isOver || world.level >= opts.maxLevels || steps >= opts.maxSteps

    println(summary(world, steps))

  private def summary(w: World, steps: Long): String =
    val levelsCleared = if w.won then Config.levelCount else w.level
    val state = if w.won then "WON" else if w.isOver then "LOST" else "STOPPED"
    List(
      s"seed=${w.run.seed}",
      s"biome=${w.run.biome}",
      s"levels_cleared=$levelsCleared",
      s"waves_reached=${w.wave}",
      s"lives=${w.lives}",
      s"gold=${w.gold}",
      s"kills=${w.kills}",
      s"leaks=${w.leaks}",
      s"state=$state",
      s"steps=$steps"
    ).mkString("\t")
```

Arg grammar: `--seed <long>` (default 24301), `--config <path>`, `--max-levels
<n>`, `--max-steps <n>` (default 1_000_000), `--no-bot`, `--help`/`-h`. Unknown
flag → stderr + usage, exit 2. Missing/non-numeric value → exit 2. `--config`
missing/parse → `config error: <msg>`, exit 1.

### Step 5 — `build.sbt`: headless `fork` + `mainClass`

```scala
lazy val headless = (project in file("headless"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "headless",
    // Separate JVM so sys.exit(code) kills only the run, not sbt.
    fork := true,
    Compile / mainClass := Some("td.HeadlessMain")
  )
```

Files
-----
- `core/src/main/scala/td/Model.scala` — `World` gains `kills`, `leaks`.
- `core/src/main/scala/td/Sim.scala` — increment kills/leaks.
- `core/src/test/scala/td/SimSmokeTest.scala` (or new `KillLeakTest.scala`).
- `headless/src/main/scala/td/Bot.scala` — new.
- `headless/src/main/scala/td/HeadlessMain.scala` — replace stub.
- `build.sbt` — headless `fork := true` + `Compile / mainClass`.

Verify
------
1. `sbt compile`.
2. `sbt test`.
3. `sbt "headless/run --seed 1"` — one tab-separated summary line, no display,
   exit 0.
4. Determinism: run twice, diff the two stdout lines → identical.
5. Different seed → `seed=` field differs (other fields identical until Phase 1).
6. Missing config → `config error: config file not found: ...`, exit 1.
7. Bad args (`--bogus`) → stderr usage, exit 2.
8. `--help` → usage on stdout, exit 0.
9. `--max-steps 100` → `state=STOPPED`, `steps=100`.
10. `--no-bot` → `kills=0`, `leaks>0`, `state=LOST`.

Notes / Open questions
----------------------
1. Kills/leaks required a core change (three spots in `Sim.step`); no copy site
   breaks (all named args).
2. Level advancement is fully inside `Sim.step`; the headless loop is a bare
   `while` over `Sim.step`.
3. Seed is echoed but not yet consumed by the sim loop; same-seed determinism is
   guaranteed, "different seed ⇒ different result" only appears once RunGen uses
   `Rng`.
4. `waves_reached = world.wave` (1-indexed wave in final level), matching
   `Phase.GameOver`. Cumulative-across-levels can be derived later if wanted.
5. `levels_cleared = Config.levelCount` when won, else `world.level` (0-indexed
   level reached). On `--max-levels` cap, `world.level >= maxLevels` and
   `state=STOPPED`.
6. `--config` double-parses (once for the detailed error, once via `Config.load`
   to apply, since `Config` has no public setter). Acceptable.
7. `fork := true` on headless is new (previously only `game` forked).
8. `--max-levels` semantics = "stop at level index N" (0-based); confirm with
   designer if it should mean "total levels including partial".
9. Arg-passing gotcha: use `sbt "headless/run --seed 1"` (whole command quoted)
   so sbt passes `--seed 1` to the forked main; unquoted makes sbt parse `--seed`
   itself.

Implementation gotchas (for the coding agent)
---------------------------------------------
- Add `kills`/`leaks` AFTER `won` so `won`'s position is unchanged.
- Only the `else` branch of `moveEnemies` gets the `leaks` bump; leave the
  `if leaked.isEmpty` branch untouched.
- `Bot.firstBuildable` must `find` (early-exit), not `for…yield`.
- With `--config`, apply the config BEFORE `Sim.initial` so the sim reads the
  right config.
- `summary` → stdout (`println`); `usage`/errors → stderr (`System.err.println`).
