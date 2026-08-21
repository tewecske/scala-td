TASK 15 — BIOME 1: "THE FRONT LAWN" (CONTENT + MINIMAL MECHANICS)
===============================================================

Goal
----
Author the first biome and its enemies/levels so the task14 briefing screen has
real content. Add the three small mechanics the design needs (per-enemy kill
reward, "weakness" via negative resist, per-run random runner speed) and keep
everything else data-driven.

Depends on
----------
All tasks 01–14 (sim, config, biomes, waves, discovery already committed).

Plan ref
--------
TASKS/task15-plan.md

Steps
-----
1. Rename core enemies Grunt→Cardboard, Swift→Paper Cut, Brute→Brick; add Crate
   (boss) + variants Paper Cut II, Cardboard II, Brick II, Crate II.
2. New biome "front-lawn" (plain, no environment, no resist theme).
3. Re-author `Config.levels` as 3 biome-1 levels × 3 waves, distinct routes.
4. Engine: EnemySpec.killReward + speedVariance; RunContext.speedMults rolled
   once per run; allow negative resists (relax Resistances.layered clamp).
5. Update all name references + add EnemyMechanicsTest; tune numbers via sim.

Files
-----
- core/src/main/scala/td/{Model,GameConfig,ConfigLoader,ConfigWriter,RunGen,Sim}.scala
- core/src/main/resources/config.json
- core/src/test/scala/td/EnemyMechanicsTest.scala (new) + renamed-fixture tests

Verify
------
- `sbt compile`; `sbt test`.
- Headless sanity later: frost tower kills Crate II faster; runner speed varies
  by seed but is deterministic per seed.

Notes
-----
- Variant entries only where a trait changes; raw growth uses WaveSpec.hpScale.
- "Hidden" traits (frost-weak, kinetic-resist) are revealed by the existing
  study/discovery system; the briefing (task14) simply never telegraphs them.
- Biome-count question (4 vs locked "3") flagged in the plan; recommend
  repurposing cube-farm into The Front Lawn.
