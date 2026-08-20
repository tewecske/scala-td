TASK 05 — BIOMES + RUN GENERATION (RunGen)
==========================================

Goal
----
Roll a full run from a seed: biome, resist profile (primary + secondary),
twists, and the enemy roster, all pre-computed once and stored on the run
context. Tutorial biome stays fixed (2 levels, always the same, skippable).

Depends on
----------
task02 (run context + Rng), task03 (biome/twist data).

Plan refs
---------
PLAN sections 4, 5, 10; context.md section 3 (roll-once).

Steps
-----
1. New `core/src/main/scala/td/RunGen.scala`:
   - `roll(seed, meta?)` -> populated RunContext (biome id, resist profile,
     twist selection, roster, and any per-run enemy scaling).
   - `tutorial(seed)` -> the fixed tutorial RunContext (always the same biome,
     no twists, fixed roster).
   - Use Rng deterministically; derive sub-rolls with Rng.split/derive so the
     mapping is stable and replayable.
2. Fill RunContext (from task02) with real fields: primary/secondary resist
   kinds + magnitudes, active twists, roster (which enemies may spawn).
3. Wire run generation into Sim.initial / newRun: a real run rolls a biome;
   the skippable tutorial is a distinct, fixed entry.
4. Keep step pure — RunGen runs once before stepping.

Files
-----
- core/src/main/scala/td/RunGen.scala (new)
- core/src/main/scala/td/Model.scala (RunContext fields)
- core/src/main/scala/td/Sim.scala (initial paths)
- game/src/main/scala/td/Main.scala (start real run vs tutorial)

Verify
------
- `sbt compile`.
- Headless: seed X always yields the same biome/resist/twist/roster.
- Tutorial is byte-identical across seeds.
- Resist theme always picks a distinct primary and secondary kind.

Notes
-----
- This only ROLLS the run; applying resist/roster/twists to the sim is task06
  (resist+env) and task11 (twists).
- Roster selection = "which enemies can appear" — actual wave composition can
  stay the existing Waves data or be derived from roster later.
