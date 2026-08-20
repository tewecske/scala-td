TASK 03 — CONFIG SCHEMA EXPANSION (DATA-DRIVEN)
===============================================

Goal
----
Extend the JSON config + loader so every new system is data-driven, and add
the data for the content that already has a home: biomes, twists, relics,
augments, and the talent tree. No sim behavior yet — only schema + data +
parsing into typed case classes.

Depends on
----------
task01 (core subproject), task02 (Rng not strictly required, but schema must
match what run generation will consume).

Plan refs
---------
PLAN sections 3, 4, 5, 7, 8, 9, 10; context.md section 6 (data before code).

Steps
-----
1. Extend GameConfig (GameConfig.scala) with typed collections, all parsed
   from config.json (or a sibling data file):
   - Biomes: id, name, roster (enemy ids), environment rule (kind + params),
     resist theme (primary + secondary DamageKind, magnitudes).
   - Twists: id, name, target tower kind, effect params (the silver-lining
     tradeoff: nerf + buff).
   - Relics: id, name, rarity (common/rare/legendary), global vs augment,
     effect params, required talent (for gated rarity).
   - Augments: id, name, effect params (the tower-socket verbs).
   - Talents: id, name, layer (filler/core/deep), cost, prereqs, effect.
2. Extend ConfigLoader to parse each new section with the existing
   JsonReader/obj/arr/s/f/i helpers and validation (unique ids, enum names
   resolve, wave-count-style consistency checks). Reuse the pattern of
   requireUnique.
3. Add Config.scala accessors for each new collection.
4. Write the actual content data for: tutorial + 3 biomes (PLAN section 5),
   the 4 twists (PLAN section 10), the relic/augment examples (PLAN section 9),
   and a first talent-tree pass (PLAN section 8: 3 cores + fillers + a couple
   of deep nodes). Numbers are tunable placeholders.

Files
-----
- core/src/main/scala/td/GameConfig.scala (new case classes)
- core/src/main/scala/td/ConfigLoader.scala (parsing + validation)
- core/src/main/scala/td/Config.scala (accessors)
- core/src/main/resources/config.json (new sections/data)

Verify
------
- `sbt compile`.
- Headless: ConfigLoader.load() succeeds; a deliberately bad id / duplicate /
  unknown enum fails with a clear error (spot-check one).

Notes
-----
- Every number the designer will tweak lives in JSON after this task.
- Keep effect representation generic (kind + params) so the editor (task13)
  can render fields without knowing every specific mechanic.
- This task must NOT wire effects into Sim yet.
