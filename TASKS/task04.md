TASK 04 — HEADLESS RUNNER + CLI
===============================

Goal
----
A command-line entry point in the `headless` subproject that steps the pure
Sim to completion with a given seed/config and prints results. This is the
designer's balance tool and the verification path for every later task.

Depends on
----------
task01 (headless subproject), task02 (seeded run), task03 (data available).

Plan refs
---------
PLAN section 3 (headless mode), section 12 Phase 0; context.md section 5.

Steps
-----
1. `headless/src/main/scala/td/HeadlessMain.scala`:
   - Args: --seed <long> (default a fixed seed), --config <path> (optional
     custom config file), --max-levels <n> (optional stop), --max-steps <n>
     (hard safety cap so a bad setup can't loop forever).
   - Build a World via Sim.initial(seed), then loop Sim.step(world, dt, empty
     cmds) with a fixed dt (e.g. Config.MaxStepDt) until won || isOver || cap.
   - Print a machine-readable-ish summary: seed, biome, levels cleared, waves
     reached, lives, gold, enemies killed, leaks, end state (WON/LOST).
2. Optionally support a simple autoplay build policy (e.g. a scripted list of
   Build commands or a naive "build nearest" bot) so sims aren't empty. Keep it
   minimal and separate from the real game's input.
3. No libGDX backend; only core's headless-safe classes (Color/JsonReader ok).

Files
-----
- headless/src/main/scala/td/HeadlessMain.scala (new)
- build.sbt (headless already depends on core; ensure a `headless/run` works)

Verify
------
- `sbt "headless/run --seed 1"` completes and prints a summary (no display).
- Same seed -> identical summary; different seed -> (usually) different.
- A config path that doesn't exist exits with a clear error, non-crashy.

Notes
-----
- This is the backbone for balancing (run thousands of seeds). Make output
  easy to grep/redirect.
- Keep the autoplay bot throwaway; real player decisions are never in Sim.
