TASK 02 — SEEDED RNG + RUN CONTEXT ON WORLD
===========================================

Goal
----
Add a deterministic seeded RNG and a run context carried on World, so a run is
a reproducible value. No new mechanics yet — just the plumbing everything else
reads from.

Depends on
----------
task01 (subprojects exist; core is the home).

Plan refs
---------
PLAN section 12 Phase 0; context.md section 3 (pure Sim, roll-once).

Steps
-----
1. New `core/src/main/scala/td/Rng.scala`: a pure, deterministic PRNG
   (xorshift is fine). Seed with a Long; provide at least `nextLong`,
   `nextInt(bound)`, `nextFloat`, `nextBoolean`, and a `split`/`derive` for
   rolling sub-decisions independently. Immutable or explicit-state (thread
   the state or use a fresh instance per decision) — never global mutable.
2. New `RunContext` (or `RunMods` placeholder) case class holding: `seed:
   Long`, `biome: String` (id, "tutorial" default for now), and empty
   placeholder fields for mods/loadout/relics/augments that later tasks fill
   in. Keep it minimal and forward-compatible.
3. Add a `run: RunContext` field to `World` (Model.scala). Update `Sim.initial`
   to accept a seed (or a `RunContext`) and default to a fixed tutorial seed.
   Update the one construction site and any World.copy chains that construct a
   fresh World.
4. Ensure `Sim.step` stays pure: it must only READ `run`, never roll within a
   step.

Files
-----
- core/src/main/scala/td/Rng.scala (new)
- core/src/main/scala/td/Model.scala (World.run + RunContext)
- core/src/main/scala/td/Sim.scala (initial(seed), unchanged step purity)
- game/src/main/scala/td/Main.scala (newRun passes a seed)

Verify
------
- `sbt compile`.
- Headless: same seed twice -> identical World after N steps (determinism).
- Different seeds -> (usually) different `run.seed`; sim still steps.

Notes
-----
- This is the substrate for run generation (task05) and the headless runner
  (task04). Keep it small but correct.
- Decide where `RunContext` lives (Model.scala is fine for now).
