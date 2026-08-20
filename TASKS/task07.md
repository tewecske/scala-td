TASK 07 — KNOWLEDGE & JOURNAL (SIM + PERSISTENCE)
=================================================

Goal
----
Track per-enemy trait discovery (UNKNOWN -> SIGHTING -> STUDIED) as the player
fights, and persist it to a journal JSON file that banks instantly (never
gated).

Depends on
----------
task06 (real effective resists exist to observe), task02 (run context), task01.

Plan refs
---------
PLAN section 6; context.md section 2 (JSON save data) + section 6.

Steps
-----
1. New trait-precision model: for each (enemy type, damage kind) track
   UNKNOWN / SIGHTING (vague: "resists frost") / STUDIED (exact percent). A
   hit of a kind upgrades SIGHTING on first hit, STUDIED after N hits (N is a
   config value).
2. Surface discovery events from Sim. Because Sim.step returns only a World,
   add an event log to World (e.g. `world.events: Vector[DiscoveryEvent]` +
   a counter) OR change step to also return events. Prefer keeping step pure
   and adding an accumulator to World that Main/journal drains each frame.
3. Also record route preference (side-taking) and abilities/affixes as
   discoverable traits (data lives in the enemy spec).
4. Journal persistence: `core/src/main/scala/td/Journal.scala` that loads
   `journal.json` (JsonReader pattern) and writes it back. Entries keyed by
   enemy id + kind + precision tier, plus biome/twist/ability knowledge.
5. Journal writes happen the moment a discovery occurs (instant, un-gated),
   regardless of whether the run is won.

Files
-----
- core/src/main/scala/td/Journal.scala (new: model + load/save)
- core/src/main/scala/td/Model.scala (event/accumulator fields)
- core/src/main/scala/td/Sim.scala (emit discoveries on hits/kills)
- core/src/main/resources/... (journal.json default or empty template)

Verify
------
- `sbt compile`.
- Headless: after stepping, World carries discovery events; journal file
  reflects SIGHTING after 1 hit and STUDIED after N hits.
- Journal persists across separate processes (write, re-load, read back).

Notes
-----
- Journal is a save file, NOT tuning config — keep it out of config.json.
- UI rendering of discoveries is task08; this task is data + sim only.
