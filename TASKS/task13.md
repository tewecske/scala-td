TASK 13 — CONFIG EDITOR APP
===========================

Goal
----
A separate desktop application that edits the game's configuration files
through a UI, with validation, sharing the game's own parser so what you see
is what the game loads.

Depends on
----------
task03 (schema), task01 (editor subproject).

Plan refs
---------
PLAN section 3 (config editor); context.md section 2 (editor = Swing, core dep).

Steps
-----
1. `editor/src/main/scala/td/ConfigEditorApp.scala` — a Swing UI (JDK built-in,
   no new deps) that loads config via ConfigLoader, renders each value as a
   labeled field, and writes back to disk.
2. Field kinds map to the schema: numbers (ranges), enums (DamageKind, layer,
   rarity), strings (names), arrays (compositions/waypoints). Use the same
   validation rules as the loader (unique ids, enum resolve, wave-count
   consistency) and refuse to save invalid files.
3. Write-back uses the same JSON shape the game reads; keep the loader the
   single source of truth (the editor must not fork the format).
4. Add an "editor/run" mainClass so `sbt "editor/run"` launches it.

Files
-----
- editor/src/main/scala/td/ConfigEditorApp.scala (new)
- build.sbt (editor mainClass)

Verify
------
- `sbt compile`.
- `sbt "editor/run"` opens (needs display); editing a value and saving produces
  a file the game still loads; an invalid edit is rejected with a message.

Notes
-----
- Keep the editable/validation logic in core where possible so it's testable
  headlessly and shared with the loader.
- Editor edits CONFIG (tuning), not save data (meta.json/journal.json).
