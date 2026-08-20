TASK 11 — TWISTS (SILVER-LINING DEBUFFS)
========================================

Goal
----
Implement the run's "twists": named tower modifications that REROUTE how a
tower plays (nerf + buff together), never a flat nerf. Rolled per run and
telegraphed in the briefing.

Depends on
----------
task05 (twists rolled into run context), task03 (twist data), task06 (sim hooks).

Plan refs
---------
PLAN section 10; context.md section 8 (names).

Steps
-----
1. For each twist from PLAN section 10 (Brittle, Backdraft, Shaped Charge,
   Ricochet), define the effect as data (kind + params) and apply it in Sim
   where the tower fires/resolves:
   - Brittle: shorter slow, slowed take +kinetic.
   - Backdraft: burn ticks faster, no stacking.
   - Shaped Charge: smaller radius, direct hits ignore armor.
   - Ricochet: every 3rd shot bounces to a second target, slower fire rate.
2. Apply the active twist(s) for the run to the affected tower kind; multiple
   twists on the same kind must compose without breaking.
3. Surface twists in the briefing display (name + description), so the player
   knows the rule changed (exact numbers may stay discoverable).

Files
-----
- core/src/main/scala/td/Sim.scala (twist application in fireTowers/resolve)
- core/src/main/scala/td/Model.scala (if Tower needs per-tower twist state)
- core/src/main/resources/config.json (twist params)
- game/src/main/scala/td/Render.scala (briefing line)

Verify
------
- `sbt compile`.
- Headless: with Brittle active, a slowed enemy takes bonus kinetic and slow
  expires sooner; Backdraft burn no longer stacks.
- A twist on a kind the player doesn't bring has no crash/effect.

Notes
-----
- The silver-lining is non-negotiable: each twist must REROUTE, not nerf.
- Keep twists readable by the journal (task07 trait recording) later if easy.
