TASK 09 — META PROGRESSION (CURRENCY + TALENT TREE)
===================================================

Goal
----
Permanent out-of-run progression: a meta currency earned every run (partial on
loss, bonus on win) and a single shared talent tree with fillers, three
mixable cores, and deep nodes. Persist unlocks to meta.json.

Depends on
----------
task03 (talent data schema), task02 (run context), task01.

Plan refs
---------
PLAN sections 2, 8; context.md section 2 (JSON save) + section 3.

Steps
-----
1. New `core/src/main/scala/td/Meta.scala`: meta currency balance + purchased
   talent ids, load/save `meta.json` (JsonReader pattern).
2. Run-end payout: award currency scaled to progress (levels cleared, waves
   survived); victory pays a bonus. Partial payout on loss (PLAN section 1).
3. Talent tree model: nodes reference the config (task03). Enforce prereqs and
   layer gating; CORES are NOT exclusive (multiple can be active at once, per
   locked decision).
4. Compute an "active effects" summary from purchased talents that Sim (and
   later the draft) reads — e.g. loadout slots, reroll availability, relic
   rarity unlocks, stat bonuses.
5. UI for spending is out of scope here (part of polish); but expose a headless
   "purchase(id)" so it can be scripted/verified.

Files
-----
- core/src/main/scala/td/Meta.scala (new)
- core/src/main/scala/td/Model.scala or new file (talent node model)
- core/src/main/scala/td/Config.scala (talent accessors, if needed)
- core/src/main/scala/td/Sim.scala or RunGen (apply active talent effects)

Verify
------
- `sbt compile`.
- Headless: finish/lose a run -> currency increases correctly; purchase a
  filler then a core (prereq enforced); meta.json persists across processes.
- Two cores can be active simultaneously.

Notes
-----
- Keep talent EFFECTS data-driven; this task applies the simple/filler ones.
  Deep talents that enable relic synergies are consumed by task10.
- Don't build the talent-tree UI screen yet (task14 polish).
