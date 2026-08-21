TASK 15 — PLAN (Biome 1: "The Front Lawn")
===========================================

Delivers the FIRST biome + its enemies/levels so task14's briefing has real
content. Also adds three small mechanics the design needs: per-enemy kill
reward, "weakness" (negative resist), and a per-run random runner speed boost.
Everything else is data.

Research (verified)
-------------------
- Enemies are global, referenced by NAME (GameConfig.scala:32-42,
  ConfigLoader.scala:162-194). Renaming Grunt/Swift/Brute ripples into biome
  `roster`s, level `composition`s, the tutorial biome, and tests
  (EnvRulesTest, MetaTest, TwistTest, DraftTest, JournalTest, ConfigCodecTest,
  ConfigTest, BranchTest).
- `Resistances.layered` clamps to [0,1] (Model.scala:22-31) so negative
  ("weak") is impossible today. Downstream math already supports negatives:
  damage `h.damage*(1-resistFrac)` (Sim.scala:618); slow `dur*(1-sr)` and
  factor `1-(1-factor)*(1-sr)` (Sim.scala:634-640) both strengthen for a
  negative resist. `vagueHint` already returns "weak" for base<0
  (Sim.scala:200-204), and the study/discovery system already reveals resists
  after StudyHits. So a "hidden frost weakness" = a negative `slow` resist on
  the boss; no new discovery code needed.
- killReward is a single global scalar (Sim.scala:182-184, applied at :423
  and :661). No per-enemy value exists.
- All RNG is rolled once at run start (context.md §3); RunGen.roll/tutorial
  build RunContext (RunGen.scala:28-57). A per-run speed multiplier belongs
  there, not inside Sim.step.
- Loader reads enemy floats directly; optional fields need a
  `v.getFloat(key, default)` (like the existing heavy/sidePreference reads).
  ConfigWriter.enemy must emit any new fields for its round-trip test.

Decisions (confirmed with user)
-------------------------------
1. Biome 1 = "The Front Lawn": plain green field, dirt road, NO environment
   rule (environment: null), NO resist theme (amounts 0/0), roster =
   [Cardboard, Paper Cut, Brick, Crate] (+ variants). This replaces the need
   for a briefing to invent content.
2. Names (on-theme, no ids renamed): Cardboard (basic), Paper Cut (runner),
   Brick (heavy), Crate (boss). These three renames map Grunt/Swift/Brute.
3. Levels: re-author the shared `Config.levels` table as biome-1's 3 levels
   (3 waves each, distinct routes). Other biomes temporarily share them until
   per-biome level tables land (a later task).
4. Mechanics added, data-driven:
   - EnemySpec.killReward: Float = 1f (multiplier on global kill reward).
   - Negative resists allowed (relax layered clamp) => boss "weak to frost".
   - EnemySpec.speedVariance: Float = 0f + RunContext.speedMults:
     Map[String,Float] rolled ONCE per run in RunGen, applied at spawn.
5. Variant entries ONLY where a trait changes (no formal unit-level system):
   Paper Cut II (faster), Cardboard II (a bit faster + more hp), Brick II
   (more hp + hidden kinetic resist), Crate II (more hp + hidden frost weak).
   Raw growth within a level uses WaveSpec.hpScale (already exists).

Enemy table (data; values are STARTING points, tune via headless sim)
---------------------------------------------------------------------
- Cardboard (basic): hp 22, speed 40, size 16, no resist, no aura.
- Paper Cut (runner): hp 10, speed 70, size 12, no resist.
- Brick (heavy): hp 90, speed 22, size 26, heavy:true, kinetic 0.35.
- Crate (boss): hp ~300, speed 14, size 30, killReward ~4.0.
- Paper Cut II: hp 10, speed 90, size 12, speedVariance 0.35.
- Cardboard II: hp 30, speed 50, size 16.
- Brick II: hp 130, speed 22, size 26, heavy:true, kinetic 0.5.
- Crate II: hp ~450, speed 14, size 32, killReward ~6.0, slow: -0.3 (weak
  to frost: takes +30% frost dmg and is slowed harder/longer).

Levels (3 each, distinct routes — STARTING values, tune later)
--------------------------------------------------------------
L1 "The Front Lawn" (3 waves): introduce all four types, boss last wave.
  W1 {Cardboard:6, Paper Cut:2} count8 hp1.0
  W2 {Cardboard:6, Paper Cut:4} count10 hp1.15
  W3 {Cardboard:6, Paper Cut:2, Brick:2, Crate:1} count12 hp1.3
L2 "The Back Path" (3 waves): more per wave, Crate II harder boss.
  W1 {Paper Cut II:10, Cardboard:2} count12 hp1.3
  W2 {Cardboard:6, Paper Cut II:4, Brick:2, Crate:1} count14 hp1.5
  W3 {Brick:2, Cardboard:4, Paper Cut II:4, Crate II:1} count16 hp1.7
L3 "The Base" (3 waves): upgraded heavy+regular, Crate II finale.
  W1 {Cardboard II:8, Paper Cut II:6} count14 hp1.6
  W2 {Brick II:3, Cardboard II:6, Paper Cut II:4, Crate:1} count16 hp1.8
  W3 {Brick II:4, Cardboard II:6, Paper Cut II:4, Crate II:1} count18 hp2.0

Routes: three distinct waypoint sets authored in Grid (data), one per level.

Steps
-----
1. Model.scala: add `speedMults: Map[String,Float] = Map.empty` to RunContext
   (near Model.scala:143). Nothing else in the model changes.
2. GameConfig.scala: EnemySpec gains `killReward: Float = 1f` and
   `speedVariance: Float = 0f` (with defaults so existing parse is unaffected).
3. ConfigLoader.scala: parseEnemy reads `killReward` and `speedVrice`? — read
   `killReward` via `v.getFloat("killReward", 1f)` and `speedVariance` via
   `v.getFloat("speedVariance", 0f)`. No schema clamp on resist values.
4. ConfigWriter.scala: enemy(...) emits `killReward` and `speedVariance` so the
   round-trip test still holds.
5. Model.scala Resistances.layered: change `math.max(0f, ...)` lower bound to
   -1f (clamp [-1f, 1f]) so negative resists survive theme layering.
6. RunGen.scala: after picking the biome, roll speedMults for each roster enemy
   with speedVariance>0: `1f + variance * rng.nextFloat()`, via a dedicated
   rng split (deterministic). tutorial: empty map (multiplier defaults to 1).
7. Sim.scala:
   - spawn: `speed = spec.baseSpeed * w.run.speedMults.getOrElse(spec.name,1f)`.
   - killReward: replace `dead.size * killReward(w)` at :423 and :661 with a
     per-enemy sum using `Config.enemySpec(e.tpe).killReward`.
8. config.json:
   - new biome "front-lawn" (name "The Front Lawn", roster above, environment
     null, resistTheme 0/0). KEEP the existing 3 real biomes; this makes 4 —
     see open question below. Alternatively repurpose one. Flag for user.
   - enemies: rename Grunt->Cardboard, Swift->Paper Cut, Brute->Brick; add the
     five new entries (Paper Cut II, Cardboard II, Brick II, Crate, Crate II).
   - levels: re-author the 3 levels as above (new names, 3 waves, routes).
   - update every other biome `roster` that referenced Grunt/Swift/Brute.
9. Tests:
   - Mechanical rename Grunt->Cardboard, Swift->Paper Cut, Brute->Brick in
     EnvRulesTest, MetaTest, TwistTest, DraftTest, JournalTest, ConfigCodecTest,
     ConfigTest, BranchTest (fixtures + assertions).
   - New EnemyMechanicsTest (headless): (a) negative slow resist => damage on a
     frost hit > base and a longer/stronger slow; (b) per-enemy killReward: a
     Crate death yields more gold than a Cardboard death; (c) speedMults:
     same seed => identical RunContext (incl. speedMults), tutorial => empty.
   - ConfigWriterTest round-trip still passes (new fields emitted + parsed).
   - EnvRulesTest "swarms are larger" wave-1 count must match re-authored L1.

Files
-----
- core/src/main/scala/td/Model.scala (RunContext.speedMults, layered clamp)
- core/src/main/scala/td/GameConfig.scala (EnemySpec fields)
- core/src/main/scala/td/ConfigLoader.scala (parse new fields)
- core/src/main/scala/td/ConfigWriter.scala (emit new fields)
- core/src/main/scala/td/RunGen.scala (roll speedMults)
- core/src/main/scala/td/Sim.scala (spawn speed, per-enemy killReward)
- core/src/main/resources/config.json (biome, enemies, levels)
- tests: core/src/test/scala/td/EnemyMechanicsTest.scala (new); update
  EnvRulesTest, MetaTest, TwistTest, DraftTest, JournalTest, ConfigCodecTest,
  ConfigTest, BranchTest.

Verify
------
1. sbt compile.
2. sbt test (incl. new EnemyMechanicsTest + all rename-updated tests +
   ConfigWriterTest round-trip).
3. Quick headless sanity (later, when sim harness is handy): a level-2 run
   where the frost tower is built should kill Crate II noticeably faster than a
   kinetic tower, confirming the hidden weakness; per-run runner speed differs
   across seeds but is identical for equal seeds.

Open questions (flag, don't block)
----------------------------------
- Biome count: adding "front-lawn" makes 4 real biomes, vs the locked "3
  biomes". Options: (a) keep 4, (b) repurpose cube-farm into The Front Lawn
  (move its openFloorPlan test to shipping-yard). Recommend (b) to honor the
  locked decision and keep the first biome the simple one.
- Boss-as-heavy: Crate is heavy:false for now (no death wreck); flip if the
  wreck-on-boss-death flavor is wanted.
- Exact hp/killReward/speed numbers above are placeholders; the user will tune
  them against the headless simulation before this task is marked done.
- Per-biome level tables (so other biomes stop sharing biome-1's levels) is a
  separate later task, not this one.
