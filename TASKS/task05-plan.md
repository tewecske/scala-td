TASK 05 — PLAN (Biomes + Run Generation, RunGen)
=================================================

Research
--------
Verified against the current codebase (task02 + task03 + task04 are already
implemented; baseline `sbt compile` + `sbt test` pass, 23 tests):

- **`RunContext`** (`Model.scala:87-98`) currently holds `seed: Long`,
  `biome: String = "tutorial"`, and four empty id vectors
  `mods/loadout/relics/augments: Vector[String]`. `RunContext.DefaultSeed =
  0x5EEDL` (`Model.scala:88`). `World.run` is a required (no-default) field
  (`Model.scala:112`).
- **`Rng`** (`Rng.scala`) is an immutable xorshift64* case class: `nextLong`
  (`:8`), `nextInt(bound)` (`:15`, throws on `bound <= 0`), `nextFloat` (`:20`),
  `nextBoolean` (`:24`), `split(): (Rng, Rng)` (`:28`), and `Rng.apply(seed)`
  normalizes 0 → a fixed nonzero constant (`:33-36`). There is **no `derive`**
  primitive — only `split` (see Gotchas).
- **`Biome`** (`GameConfig.scala:122-128`): `id, name, roster: Vector[String]`
  (enemy **names**), `environment: Option[Effect]`, `resistTheme: ResistTheme`.
- **`ResistTheme`** (`GameConfig.scala:112-117`): `primary: DamageKind,
  primaryAmount: Float, secondary: DamageKind, secondaryAmount: Float`.
- **`Twist`** (`GameConfig.scala:133-138`): `id, name, targetTower: String,
  effect: Effect`.
- **`DamageKind`** (`Model.scala:32-33`): `Kinetic, Fire, Slow, Splash, Explosion`.
- **Config accessors exist** (`Config.scala`): `biomes`/`biome(id)` (`:60-62`),
  `twists`/`twist(id)` (`:65-67`), plus `enemies` via `Config.game.enemies`.
- **Loader already guarantees the invariants we need**:
  - at least one biome, and one with `id == "tutorial"` (`ConfigLoader.scala:94-96`);
  - biome resist theme `primary != secondary` (`ConfigLoader.scala:265-266`);
  - twist `targetTower` resolves to an existing tower name (`ConfigLoader.scala:272`).
- **Bundled `config.json`**: 4 biomes — `tutorial` (resist theme Kinetic 0.0 /
  Fire 0.0, roster `Grunt, Swift, Ember, Frostling`) and 3 real biomes
  `cube-farm` / `shipping-yard` / `cardboard-wastes` with non-zero distinct
  themes (`config.json:99-128`); 4 twists `brittle/backdraft/shaped-charge/
  ricochet`, each targeting a distinct tower (`config.json:129-138`).
- **`Sim.initial(seed: Long = RunContext.DefaultSeed)`** (`Sim.scala:12-25`) is the
  ONLY `World` construction site and sets `run = RunContext(seed = seed)` (so
  biome defaults to `"tutorial"` today). `Sim.step` (`Sim.scala:27-37`) never
  reads RNG — it only reads `w.run` and Config.
- **`Main.newRun`** (`Main.scala:111-113`) = `Phase.Playing(Sim.initial(
  System.nanoTime()))`. The Menu phase (`Main.scala:49-51`) has only one button
  (`Ui.Play`, `Config.scala:133`); `Render.drawMenu` (`Render.scala:110-124`)
  draws a single PLAY button.
- **`HeadlessMain`** (`headless/.../HeadlessMain.scala:71,87`) calls
  `Sim.initial(opts.seed)` and prints `biome=${w.run.biome}` — so once
  `Sim.initial(seed)` rolls, headless automatically reports the rolled biome.
- **Existing tests that must keep passing**: `SimSmokeTest` asserts
  `Sim.initial(RunContext.DefaultSeed).run.seed == DefaultSeed` (`:5,9`);
  `RngTest` asserts `Sim.initial(s)` twice equal + N steps equal and
  `Sim.initial(1L).run.seed == 1L` (`:50-70`). Neither asserts `biome`, so both
  survive `Sim.initial` rolling a real biome (the seed is still echoed).
  `ConfigTest`/`MessagesTest` are untouched.

Decisions (confirmed)
---------------------
1. **Populate `RunContext` directly (not a separate "run roll" type), with three
   new defaulted fields after `biome`:** `resistTheme: ResistTheme` (a snapshot),
   `twists: Vector[String]` (twist ids), `roster: Vector[String]` (enemy names).
   Rationale: matches task02's "fill RunContext with real fields" and task06's
   "layer the run's primary/secondary resist kinds" (task06.md step 1), reuses the
   existing `ResistTheme` value type instead of four loose fields, and the id
   vectors match the existing `mods/loadout/relics/augments` style. `resistTheme`
   reuses `GameConfig.ResistTheme` rather than separate `primaryResist`/
   `secondaryResist` fields (the prompt's "e.g." is just an example; `ResistTheme`
   already encodes kinds + magnitudes).
2. **Resist profile is COPIED from `biome.resistTheme`, never rolled fresh.**
   Rolling the biome IS rolling the resist profile (resist theme is a property of
   the biome in config). Copying verbatim is deterministic + data-driven, and the
   loader already guarantees `primary != secondary` for free. Rolling magnitudes
   would need a variance source that doesn't exist yet and could reintroduce
   primary==secondary collisions — not worth it. `roll` returns
   `RunContext(resistTheme = biome.resistTheme, ...)`.
3. **One twist per real run**, picked deterministically via `nextInt(twists.size)`
   on an independent sub-stream; tutorial rolls **zero** twists. The twist count is
   a single named constant (`TwistsPerRun = 1`) — trivial to make 0 or N later.
   No loadout gating (that's a later task; we only record twist ids). Guard the
   empty-`Config.twists` case (defensive; the bundled config has 4).
4. **`RunGen.tutorial(seed: Long = RunContext.DefaultSeed): RunContext`** — a
   function, not a constant, so the World still carries a valid seed (headless
   summary, reproducibility) while every game field is fixed: `biome="tutorial"`,
   `resistTheme = Config.biome("tutorial").resistTheme`, `roster =
   Config.biome("tutorial").roster`, `twists = Vector.empty`. "Byte-identical
   across seeds" = all fields except the echoed `seed` are equal (test asserts
   `tutorial(a).copy(seed = 0L) == tutorial(b).copy(seed = 0L)`).
5. **`Sim.initial` gains a `RunContext` overload; the `Long` overload routes
   through `RunGen.roll`.** `Sim.initial(seed)` → `initial(RunGen.roll(seed))`;
   `Sim.initial(run)` builds the World with `run = run`. All existing test call
   sites pass a `Long`, so nothing breaks. `Main.newRun()` needs **no change**
   (it already calls `Sim.initial(System.nanoTime())`, which now rolls). The
   tutorial becomes a distinct `startTutorial()` entry wired to a `T` key in the
   Menu phase (minimal, no Render/messages churn); the proper Tutorial button +
   briefing is deferred to the menu/briefing task.
6. **`Sim.step` stays pure** — RunGen runs exactly once (inside `Sim.initial`
   or directly), snapshots everything onto `RunContext`, and `step` only reads
   `w.run`. Per-run enemy scaling is **not** added now (no config source; its
   application is task06's environment rules).

Steps
-----

### Step 1 — `Model.scala`: extend `RunContext`

Insert three defaulted fields after `biome`, before `mods` (keeping `seed`
required and `biome` the only other existing defaulted field; all four id
vectors keep their defaults). `ResistTheme` is already in scope (same package).

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
    augments: Vector[String] = Vector.empty
)
```

The `resistTheme` default mirrors the tutorial theme (`config.json:105`) so a
bare `RunContext(seed = s)` still compiles and is well-formed.

### Step 2 — New `core/src/main/scala/td/RunGen.scala`

```scala
package td

/** Rolls a full run from a seed, once, before any step runs. The result is
  * snapshotted onto RunContext so Sim.step only reads it (never re-rolls).
  */
object RunGen:

  /** How many twists a real run gets. Tunable placeholder; move to config if
    * the designer wants a difficulty curve.
    */
  private val TwistsPerRun = 1

  /** Roll a real run: a non-tutorial biome (its resist theme + roster) and one
    * twist. Deterministic: same seed => identical RunContext.
    */
  def roll(seed: Long): RunContext =
    val (biomeRng, twistRng) = Rng(seed).split()
    val biome = pickBiome(biomeRng)
    RunContext(
      seed = seed,
      biome = biome.id,
      resistTheme = biome.resistTheme,
      twists = pickTwists(twistRng),
      roster = biome.roster
    )

  /** The fixed tutorial: always the "tutorial" biome, no twists, its own roster.
    * Identical gameplay for every seed; only the echoed seed differs.
    */
  def tutorial(seed: Long = RunContext.DefaultSeed): RunContext =
    val biome = Config.biome("tutorial")
    RunContext(
      seed = seed,
      biome = biome.id,
      resistTheme = biome.resistTheme,
      twists = Vector.empty,
      roster = biome.roster
    )

  private def pickBiome(rng: Rng): Biome =
    val real = Config.biomes.filter(_.id != "tutorial")
    if real.isEmpty then Config.biome("tutorial")
    else
      val (idx, _) = rng.nextInt(real.size)
      real(idx)

  private def pickTwists(rng: Rng): Vector[String] =
    val all = Config.twists
    if all.isEmpty then Vector.empty
    else
      val (idx, _) = rng.nextInt(all.size)
      Vector(all(idx).id)
```

Design notes baked in:
- `Rng(seed).split()` gives two independent streams so the biome draw and the
  twist draw don't shift each other if more sub-rolls are added later (stable,
  replayable mapping).
- `pickBiome` excludes `"tutorial"` from real rolls and falls back to tutorial
  if a config only ships the tutorial biome (guards `nextInt(0)`).
- Resist/roster are copied straight from the chosen `Biome` (Decisions 2/3).

### Step 3 — `Sim.scala`: two overloads of `initial`

Replace the current single `initial` (`Sim.scala:12-25`):

```scala
def initial(seed: Long = RunContext.DefaultSeed): World =
  initial(RunGen.roll(seed))

def initial(run: RunContext): World = World(
  level = 0,
  towers = Vector.empty,
  enemies = Vector.empty,
  shots = Vector.empty,
  gold = Config.StartGold,
  lives = Config.StartLives,
  wave = 0,
  spawnQueue = Vector.empty,
  spawnTimer = 0f,
  breakTimer = Waves.breakTime,
  nextId = 1L,
  run = run
)
```

`Sim.step` is untouched — it already only reads `w.run` and never consumes RNG
(`Sim.scala:27-37`). Overload resolution is unambiguous (`Long` vs `RunContext`).

### Step 4 — `Main.scala`: real run vs tutorial

`newRun()` is unchanged (now rolls automatically). Add a tutorial entry:

```scala
  /** Every run starts from a clean World, so nothing leaks across retries. */
  private def newRun(): Phase =
    fast = false
    Phase.Playing(Sim.initial(System.nanoTime()))

  private def startTutorial(): Phase =
    fast = false
    Phase.Playing(Sim.initial(RunGen.tutorial(System.nanoTime())))
```

Wire a `T` key in the Menu phase (`Main.scala:49-51`) as the distinct tutorial
entry (placeholder until the briefing/menu task adds a real button):

```scala
      case Phase.Menu =>
        gfx.drawMenu()
        if clicked && Ui.Play.contains(mx, my) then newRun()
        else if Gdx.input.isKeyJustPressed(Input.Keys.T) then startTutorial()
        else Phase.Menu
```

### Step 5 — Test: `core/src/test/scala/td/RunGenTest.scala` (new)

```scala
package td

class RunGenTest extends munit.FunSuite:

  test("same seed rolls the same run") {
    Seq(0x5EEDL, 1L, 42L, 0L, 123456789L).foreach { s =>
      assertEquals(RunGen.roll(s), RunGen.roll(s))
    }
  }

  test("different seeds spread across the real biomes") {
    val biomes = (1L to 50L).map(RunGen.roll(_).biome).toSet
    assert(biomes.size >= 2)            // 3 real biomes, 50 seeds
    assert(!biomes.contains("tutorial")) // real runs never roll the tutorial
  }

  test("tutorial is identical across seeds except the echoed seed") {
    assertEquals(RunGen.tutorial(1L).copy(seed = 0L), RunGen.tutorial(2L).copy(seed = 0L))
    assertEquals(RunGen.tutorial(7L).biome, "tutorial")
    assert(RunGen.tutorial(7L).twists.isEmpty)
  }

  test("resist theme always has distinct primary and secondary kinds") {
    (1L to 50L).foreach { s =>
      val rt = RunGen.roll(s).resistTheme
      assertNotEquals(rt.primary, rt.secondary)
    }
    assertNotEquals(RunGen.tutorial(1L).resistTheme.primary, RunGen.tutorial(1L).resistTheme.secondary)
  }

  test("roster is the chosen biome's roster and references existing enemies") {
    val enemyNames = Config.enemies.map(_.name).toSet
    (1L to 50L).foreach { s =>
      val run   = RunGen.roll(s)
      val biome = Config.biome(run.biome)
      assertEquals(run.roster, biome.roster)
      assert(run.roster.nonEmpty)
      run.roster.foreach(e => assert(enemyNames.contains(e)))
    }
  }

  test("twists resolve to existing Config.twists ids") {
    val twistIds = Config.twists.map(_.id).toSet
    (1L to 50L).foreach { s => RunGen.roll(s).twists.foreach(id => assert(twistIds.contains(id))) }
    assert(RunGen.roll(7L).twists.nonEmpty)  // TwistsPerRun = 1
  }

  test("Sim.initial(seed) carries the rolled run; initial(run) uses it verbatim") {
    assertEquals(Sim.initial(42L).run, RunGen.roll(42L))
    val t = Sim.initial(RunGen.tutorial(9L))
    assertEquals(t.run.biome, "tutorial")
    assertEquals(t.run.seed, 9L)
  }
```

Files
-----
- `core/src/main/scala/td/RunGen.scala` — new (`roll`, `tutorial`, `pickBiome`,
  `pickTwists`).
- `core/src/main/scala/td/Model.scala` — `RunContext` gains `resistTheme`,
  `twists`, `roster` (all defaulted).
- `core/src/main/scala/td/Sim.scala` — `initial` split into `initial(seed)` and
  `initial(run)`; `step` unchanged.
- `game/src/main/scala/td/Main.scala` — add `startTutorial()` + `T` key in Menu.
- `core/src/test/scala/td/RunGenTest.scala` — new munit tests.

No changes to `config.json`, `GameConfig.scala`, `ConfigLoader.scala`,
`Config.scala`, `HeadlessMain.scala`, or `Render.scala` — all data and accessors
needed by RunGen already exist from task03.

Verify
------
1. `sbt compile`.
2. `sbt test` — existing 23 tests still pass (SimSmokeTest/RngTest only assert
   `run.seed`, which is still echoed) plus `RunGenTest`.
3. Determinism: `RunGen.roll(s) == RunGen.roll(s)` (test) and
   `sbt "headless/run --seed 1"` twice → identical summary line; the `biome=`
   field now reports a rolled real biome (no longer always `tutorial`).
4. Tutorial fixed: `RunGen.tutorial(a).copy(seed = 0L) ==
   RunGen.tutorial(b).copy(seed = 0L)` across seeds.
5. Resist distinct: `resistTheme.primary != secondary` for every rolled/tutorial
   run (also guaranteed by the loader, re-asserted in test).
6. Twists resolve: every `run.twists` id is in `Config.twists`.
7. `sbt "game/run"` still launches (needs a display); `T` from the menu starts a
   tutorial run.

Notes / Open questions
----------------------
1. **Tutorial "2 levels" is NOT implemented here.** `Sim.initial` always starts
   at level 0 and `completeLevel` uses `Config.levelCount` (3); there is no
   config field linking biomes to level ranges yet. RunGen only fills the run
   context; level curation for the tutorial is a later data/design concern
   (PLAN section 5's "2 fixed levels").
2. `roll` takes no `meta` parameter yet: nothing it rolls (biome/resist/twist/
   roster) depends on meta progression. Grow the signature when biome-lock
   talents or meta-gated drafting land.
3. Twist count is a code constant (`TwistsPerRun = 1`), not config. Move it to
   `config.json` (a `runGen`/`twistsPerRun` block) if the designer wants to tune
   it — note "data before code" (context.md section 6) but the task did not
   request a new schema field.
4. `resistTheme` is stored as one `ResistTheme` value, not four loose fields. If
   the journal (task07) prefers named accessors, add `RunContext.primaryResist`
   etc. as helpers — no data change.
5. Per-run enemy scaling is deliberately absent (no data source; applied in
   task06 via environment rules). `roster` is recorded but does not yet filter
   wave composition (task05 Notes say that can stay `Waves`-driven for now).
6. The `T`-key tutorial entry is a placeholder; the briefing/menu task should
   add a visible Tutorial button (needs `Render.drawMenu` + a `Ui` rect + a
   `Messages` key).
7. "Byte-identical across seeds" is defined as *all fields except the echoed
   `seed`*. If a future reader wants a literally identical RunContext, switch
   `tutorial` to a `val` constant (drop the `seed` param) — but then the World's
   `run.seed` would be fixed rather than the requested seed.

Implementation gotchas
----------------------
- **Only `split()` exists, not `derive`.** `Rng.scala` has no `derive` primitive
  despite the task prompt's wording; use `split(): (Rng, Rng)` for independent
  sub-streams. Add a `derive` helper to `Rng` only if a later task needs named
  streams.
- `Rng.nextInt` **throws** on `bound <= 0` (`Rng.scala:16`) — hence the
  `pickBiome` empty-guard and `pickTwists` empty-guard before calling it.
- `RunContext` fields must be added **after `biome` and before `mods`** and all
  defaulted, so any lingering `RunContext(seed = seed)` / `.copy(...)` call still
  compiles (currently only `Sim.initial` builds one, and it is rewritten to use
  `RunGen`).
- `Sim.initial` overloads: the `Long` default stays on the `seed` overload only;
  do NOT give `initial(run)` a default. `Sim.initial()` resolves to the `Long`
  overload (default seed) — matches the old behavior.
- `pickBiome` must `filter(_.id != "tutorial")` from `Config.biomes`, and the
  tutorial must come from `Config.biome("tutorial")` (exists per loader
  `ConfigLoader.scala:95-96`), not from a hardcoded roster.
- The `resistTheme` default references `DamageKind.Kinetic`/`DamageKind.Fire`
  (defined above `RunContext` in `Model.scala:32-33`) — no import or init-order
  issue.
- Don't touch `Sim.step`; RunGen must be invoked only from `Sim.initial` /
  `Main` (once), preserving the "roll-once, step reads only" rule (context.md
  section 3).
- The "different seeds spread across biomes" test asserts `size >= 2`, not `== 3`,
  to stay robust against a pathological (but still deterministic) mapping; the
  implementer should confirm the chosen `split`/`nextInt` combo actually visits
  ≥2 biomes over `1L..50L` (it will with xorshift's distribution).
