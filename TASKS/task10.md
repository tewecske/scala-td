TASK 10 — IN-RUN DRAFT (RELICS + AUGMENTS + SOCKET + REROLL)
============================================================

Goal
----
Between levels, offer 1-of-3 relics (global) or augments (one socket per
tower), with rerolls costing in-run gold and gated behind a talent. Deep
talents unlock/amplify rare relics. This is the per-run build layer.

Depends on
----------
task09 (talent-driven reroll/rarity + active effects), task05 (run context),
task03 (relic/augment data).

Plan refs
---------
PLAN sections 8, 9; context.md section 3 (canBuild funnel).

Steps
-----
1. Relic/augment model: global relics apply to all/some actors for the rest of
   the run; augments attach to ONE tower via its single socket (extend Tower
   with `augment: Option[String]`).
2. Draft flow: at level completion, roll 3 offers from the pool, filtered by
   rarity vs the player's deep talents (a shallow player can't roll
   legendary). Player picks one; a talent unlock enables paying GOLD to reroll
   the offer.
3. Apply global relic effects in Sim (read from run context). Apply augment
   effects where the tower fires/resolves (fireTowers / moveProjectiles).
4. New Command cases (pick relic, attach augment, reroll) per Model.scala's
   existing convention; keep Sim.step pure.
5. All effect params come from config (task03).

Files
-----
- core/src/main/scala/td/Model.scala (Tower.augment, new Command cases, Phase)
- core/src/main/scala/td/Sim.scala (draft state + effect application)
- core/src/main/scala/td/RunGen.scala or new Draft.scala (offer rolling)
- game/src/main/scala/td/Render.scala (offer UI, attach UI)

Verify
------
- `sbt compile`.
- Headless: with no reroll talent, reroll is unavailable; with it, reroll
  spends gold and re-rolls the offer.
- A legendary relic only appears when the matching deep talent is owned.
- An augment attaches to exactly one tower; a second socket is refused.

Notes
-----
- Relics/augments supply the VERB; deep talents the ENGINE (context rule).
  Keep this division visible in the data.
- Reroll is gold-based and talent-gated (locked decision).
