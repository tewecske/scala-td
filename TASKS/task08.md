TASK 08 — LIVE DISCOVERY UI + END-SCREEN RECAP
==============================================

Goal
----
Make discovery visible during play and consolidate it at run end: damage
popups ("RESIST"/"IMMUNE"), first-meet name flashes, and an end screen that
highlights NEW journal entries and gives a death recap.

Depends on
----------
task07 (discovery events + journal), task01 (game subproject).

Plan refs
---------
PLAN sections 5, 6; context.md section 8 (naming/uniform theme).

Steps
-----
1. In Render (game subproject), draw floating damage/status text at the hit
   point for a short lifetime. Use the existing BitmapFont (no new assets).
2. First meet: when an enemy type is first seen in a run, flash its name (or
   "???" until first killing blow, per PLAN section 6).
3. End-screen consolidation: after a run, list discoveries with NEW items
   highlighted and already-known items dimmed.
4. Death recap: summarize cause of death (e.g. "78% of leaks were
   fire-resistant; you brought no kinetic") using the journal + run data.
5. Keep all text on-theme (square/box names) and reuse Config colors.

Files
-----
- game/src/main/scala/td/Render.scala (popups, flashes, recap screen)
- game/src/main/scala/td/Main.scala (feed events, new end-screen Phase if any)
- core/src/main/scala/td/Model.scala (Phase cases for recap/consolidation)

Verify
------
- `sbt compile`; visual check via `sbt "game/run"` (needs display).
- Hitting an enemy with a resisted kind shows "RESIST"/"IMMUNE".
- End screen shows new vs old journal entries correctly.

Notes
-----
- This is pure presentation; no rules change.
- Coordinate colors/text with the naming theme (context.md section 8).
