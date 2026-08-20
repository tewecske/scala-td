TASK 14 — POLISH (NAMING, COLORS, BRIEFING, TUTORIAL)
=====================================================

Goal
----
Make the game legible and cohesive: a briefing screen for the run, a skippable
2-level tutorial, the talent-tree/meta UI, and a final naming/color pass that
commits to the square/box theme.

Depends on
----------
task05 (briefing content), task09 (talent UI), task08 (recap UI), and earlier
mechanics as available.

Plan refs
---------
PLAN sections 2, 4, 5, 7; context.md section 8 (naming theme).

Steps
-----
1. Briefing screen: shows biome name, environment rule, resist theme, and
   twists (telegraphed info only — never spoil discoverable exacts).
2. Loadout picker: choose which towers to bring (gated by talent loadout
   slots). Feeds canBuild (single source of truth).
3. Skippable tutorial: 2 fixed levels teaching building, targeting,
   resistances, live-discovery popups, the journal, and drafting.
4. Talent-tree / meta screen: spend currency, browse fillers/cores/deep nodes,
   show active effects.
5. Final naming + color pass: ensure every player-facing name is on-theme
   (square/box/edge/angle), colors from Config, and the HUD reads cleanly.
6. Optional (PLAN section 13): retheme damage kinds LAST, only if desired.

Files
-----
- game/src/main/scala/td/Main.scala (new Phases + screen routing)
- game/src/main/scala/td/Render.scala (briefing/loadout/talent/tutorial UI)
- core/src/main/scala/td/Model.scala (Phase cases)
- core/src/main/resources/config.json (tutorial data, palette, copy)

Verify
------
- `sbt compile`; visual pass via `sbt "game/run"`.
- Tutorial is skippable and identical every run.
- Briefing never reveals discoverable exacts (only the telegraphed info).

Notes
-----
- This task ties together everything; only start when earlier mechanics exist.
- The tutorial's fixed 2 levels are content data, not special-cased code.
