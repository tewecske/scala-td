TASK 02 — PLAN
==============

Research (via subagent) confirms: `World` has exactly ONE construction site
(`Sim.initial` at `Sim.scala:12`), TWO callers of `Sim.initial`
(`Main.scala:108`, `SimSmokeTest.scala:5`), and all 15 `World.copy(...)` calls
in `Sim.scala` pass named fields, so a new `run` field flows through every copy
with zero edits. There is currently NO randomness anywhere and no existing
`Rng`/`RunContext`/`seed` symbol.

Decisions (confirmed)
---------------------
- Empty placeholder fields in `RunContext` are fine (`Vector[String]` ids).
- GUI seed source: `System.nanoTime()` (each run differs).
- Tests use MULTIPLE fixed seeds (not a single fixed seed).

Steps
-----

1. New `core/src/main/scala/td/Rng.scala` — immutable xorshift64* (no global
   mutable):
   - `nextLong(): (Long, Rng)`
   - `nextInt(bound: Int): (Int, Rng)`   // [0, bound)
   - `nextFloat(): (Float, Rng)`         // [0, 1)
   - `nextBoolean(): (Boolean, Rng)`
   - `split(): (Rng, Rng)`               // two independent streams
   - `Rng(seed)` companion normalizes seed 0 to a fixed nonzero constant.
   - Algorithm: `x ^= x>>>12; x ^= x<<25; x ^= x>>>27; x * 0x2545F4914F6CDD1DL`,
     guarding against a zero state.

2. `Model.scala` — add `RunContext` and `run: RunContext` on `World`:

     object RunContext:
       val DefaultSeed: Long = 0x5EEDL

     case class RunContext(
         seed: Long,
         biome: String = "tutorial",
         mods: Vector[String] = Vector.empty,
         loadout: Vector[String] = Vector.empty,
         relics: Vector[String] = Vector.empty,
         augments: Vector[String] = Vector.empty
     )

   `run` goes after `nextId`, before `won = false` (keeps `won` the only
   defaulted field). Required (no default).

3. `Sim.scala`:
   - `def initial(seed: Long = RunContext.DefaultSeed): World`, builds
     `run = RunContext(seed = seed)`.
   - `Sim.step` unchanged — already pure; only reads, never mutates `run`.

4. `Main.scala`:
   - `newRun()` -> `Phase.Playing(Sim.initial(System.nanoTime()))`.

5. Tests (multiple fixed seeds):
   - Update `SimSmokeTest.scala` to call `Sim.initial(RunContext.DefaultSeed)`
     and assert `w.run.seed`.
   - New `core/src/test/scala/td/RngTest.scala` using fixed seeds
     (`0x5EEDL`, `1L`, `42L`, `0L`):
     - same seed -> identical `nextLong` sequence; different seeds -> different
       sequences; seed 0 is normalized (no zero-state lock).
     - `nextInt` bounds, `nextFloat` range, `split` independent streams.
     - determinism: for each seed, `Sim.initial(s)` twice equal; after N
       `Sim.step(_, dt, Vector.empty)` still equal; different seeds ->
       `run.seed` differs but sim steps.

Files
-----
- core/src/main/scala/td/Rng.scala (new)
- core/src/main/scala/td/Model.scala (RunContext + World.run)
- core/src/main/scala/td/Sim.scala (initial(seed); step purity unchanged)
- game/src/main/scala/td/Main.scala (newRun passes a seed)
- core/src/test/scala/td/RngTest.scala (new)
- core/src/test/scala/td/SimSmokeTest.scala (updated)

Verify
------
- `sbt compile`.
- `sbt test`.
- `sbt "game/run"` still launches (needs a display).
