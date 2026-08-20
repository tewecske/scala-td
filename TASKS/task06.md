TASK 06 — ENVIRONMENT RULES + RESIST THEME IN SIM
=================================================

Goal
----
Apply the rolled resist profile and the biome environment rule to the actual
simulation: enemies get run-wide resistances, and the environment rule changes
HOW the game plays (not just numbers). This is where the "no two runs alike"
feel first becomes real.

Depends on
----------
task05 (rolled RunContext), task03 (biome environment-rule data).

Plan refs
---------
PLAN sections 4, 5; context.md section 3 (single source of truth).

Steps
-----
1. Resist theme: when Sim.spawn builds an Enemy, layer the run's
   primary/secondary resist kinds onto the enemy's base `spec.resist`
   (e.g. primary kind -> high/immune, secondary -> medium). Do NOT replace the
   per-type resist; combine them.
2. Environment rules: implement the 3 tutorial/real biome rules from PLAN
   section 5 as data-driven effects evaluated in Sim:
   - Open Floor Plan (armor while near each other),
   - Crushed (dying heavies leave a temporary movement hazard),
   - Recycling (leak cost / extra gold + larger swarms).
   Represent each as a kind + params (schema from task03) and dispatch in the
   appropriate Sim stage (spawn / tickEffects / moveEnemies / auras).
3. All new constants are config values, not literals.

Files
-----
- core/src/main/scala/td/Sim.scala (spawn + env-rule hooks)
- core/src/main/scala/td/Model.scala (any new enemy/world state fields)
- core/src/main/resources/config.json (env-rule params)

Verify
------
- `sbt compile`.
- Headless: same seed -> enemies' effective resist matches the rolled theme.
- Each environment rule demonstrably changes outcomes (spot-check via headless
  summary diffs between biomes).

Notes
-----
- Keep resist application readable so the journal (task07) can later report
  the exact effective values the player actually faced.
- Don't implement twists here (task11).
