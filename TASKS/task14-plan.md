TASK 14 — PLAN (Polish: naming, colors, briefing, tutorial) — REFINED
======================================================================

This plan was first written before briefing/loadout/talents existed. Commit
`bb043fb` ("briefing, loadout, talents") has since landed all three screens.
This revision records what that commit actually did (including where it
diverged from the original plan) and narrows the remaining work to what is
still needed to satisfy task14's stated goal: "a briefing screen, a skippable
2-level tutorial, the talent-tree/meta UI, and a final naming/color pass."

Status snapshot (after bb043fb)
-------------------------------
IMPLEMENTED:
- `Phase.Briefing(w: World)`, `Phase.Loadout(run, selected)`, `Phase.Talents(data)`
  all exist (`Model.scala:197-199`).
- `Render.drawBriefing` / `drawLoadout` / `drawTalents` exist and use
  `Config.*Color`; `Ui` has `BriefingBegin/Back`, `LoadoutRow/Confirm/Back`,
  `TalentRow/Talents/TalentsBack`.
- Loadout gating in `Sim.canBuild` (`Sim.scala:95`):
  `(w.run.loadout.isEmpty || w.run.loadout.contains(tpe)) && ...`.
- Pool-aware selection in `Main` via `Config.LoadoutPool(loadout)`
  (Playing + Paused branches), so the build list, number keys, and `canBuild`
  agree.
- Briefing + talent message keys added to `messages_en.properties`.
- Enemy names already on-theme (Cardboard, Paper Cut, Brick, Ember, Frostling,
  Commander, Edgelord, Crate, ...).
- Loadout is **config-driven** via `LoadoutConfig(unlocked, locked,
  maxSelected, defaultSelected)` parsed in `ConfigLoader` and serialized by
  `ConfigWriter` (round-trip safe).

DIVERGENCES FROM THE ORIGINAL PLAN (note so no one re-implements them):
- The plan proposed gating loadout by `BaseLoadoutSlots +
  TalentEffects.loadoutSlots` (a `baseSlots` config + a `loadout-slots` talent).
  The landed design instead uses explicit `unlocked`/`locked` lists per config.
  The `loadout-slots` talent and `BaseLoadoutSlots` idea are OBSOLETE — do not
  add them.
- The plan had `Briefing(run: RunContext)`. Landed code uses `Briefing(w: World)`
  and routes ALL runs (real + tutorial) through briefing → loadout. The plan's
  "tutorial skips the loadout picker" decision (decision 6) was NOT followed;
  the tutorial now also picks a loadout.
- The plan's biome-aware `tutorialLevels` / `levelsFor` resolver was NOT built.
  The tutorial biome currently plays the shared 3-level `Config.levels` table.

What remains to call task14 "done"
-----------------------------------
R1 — NAMING PASS (the clearly-incomplete sub-goal)
  Towers are still off-theme: `Basic / Fire / Frost / Cannon / Bombard`.
  Real levels are still generic: `The Front Lawn / The Back Path / The Base`.
  The window + menu title is still `Tower Defense` / `TOWER DEFENSE`.
  Enemies, biome names (The Sandbox / The Cube Farm / The Shipping Yard /
  The Cardboard Wastes), twists, talents, relics, and augments are already
  on-theme — leave them.
  Proposed table (ids stay stable; rename only `name`/`targetTower`/`roster`/
  `composition` strings):
    Basic    -> Straightedge      (kinetic, the "rule" you measure by)
    Fire     -> Matchbox          (fire in a box)
    Frost    -> Ice Cube          (frost, cubic)
    Cannon   -> Hole Punch        (splash punches through a group)
    Bombard  -> Jack-in-the-Box   (explosion pops out of a box)
    The Front Lawn -> The Mailroom
    The Back Path   -> The Loading Dock
    The Base        -> The Corner Office
  Title -> `Square Defense` via `Messages.t("game.title")` (add the key).
  Mechanically: edit `config.json` + every test that pins an old name
  (`MetaTest`, `TwistTest`, `DraftTest`, `EnvRulesTest`, `BranchTest`,
  `JournalTest`, `ConfigCodecTest`, `ConfigTest` fixtures) with a
  `replaceAll` per file. Keep ids untouched.

R2 — TUTORIAL TEACHING SCENARIO + SKIPPABILITY (open decision)
  The original locked decision ("2 fixed levels, identical every run,
  skippable, teaches building/targeting/resistances/popups/journal/drafting,
  skips loadout") is NOT met. Today the tutorial is just a no-twist biome
  playing the shared 3 real levels, with the same briefing+loadout flow and
  NO skip button.
  Two viable paths — pick one:
    (a) LIGHT (recommended, low-risk): keep the shared-levels approach but
        (i) add a `SKIP TUTORIAL` button on `drawBriefing` + on `drawPaused`
        when `w.run.biome == "tutorial"`, both returning to `Phase.Menu`; and
        (ii) optionally surface the existing live-discovery/joural affordances
        more prominently during the tutorial run. No new level data, no biome
        resolver. Satisfies "skippable tutorial" but not "2 fixed levels".
    (b) FULL (matches original plan): add a `tutorialLevels` array +
        `Config.levelsFor/biome` resolver and route `Waves`/`Sim`/`Meta`/
        `Render`/`HeadlessMain` through it by `run.biome`; author 2 simple
        levels (level 1 must include a non-zero base-resist enemy so the
        RESIST popup + journal entry fire; level 2 covers drafting via the
        automatic between-level draft). Add `SKIP TUTORIAL` as in (a).
        Keep the tutorial loadout gate per the landed design (no special-case
        skip of loadout unless desired).
  Either way: tutorial must stay seed-independent (`RunGen.tutorial` already
  is) and skippable from briefing + pause.

R3 — COLOR / HUD CONTRAST PASS (minor)
  No schema change expected (colors are already data). Verify the three new
  screens reuse `Config.*Color` consistently and that `drawHud` uses
  `TextColor` vs `DimText` readably. Re-run `ConfigWriterTest` round-trip to
  confirm the R1 data edit is lossless. Usually already fine; treat as a
  visual QA pass, not code work.

R4 — DAMAGE-KIND RETHEME (PLAN §13, deferred/optional)
  Cosmetic only: ripples into `DamageKind`, resist JSON keys, `Messages.kind.*`,
  and ~every resist test for zero mechanical gain. Skip unless explicitly
  wanted.

Files touched by the remaining work
-----------------------------------
- `core/src/main/resources/config.json` — R1 tower/level rename; R2(b) `tutorialLevels`.
- `core/src/main/resources/messages/messages_en.properties` — R1 `game.title`; R2 `briefing.skipTutorial`.
- `game/src/main/scala/td/Main.scala` — R1 window title; R2 skip buttons.
- `game/src/main/scala/td/Render.scala` — R1 menu title; R2 `drawBriefing`/`drawPaused` skip.
- `core/src/main/scala/td/Config.scala` — R2(b) `levelsFor`/resolver (only if path b).
- `core/src/main/scala/td/{Waves,Sim,Meta}.scala`, `headless/.../HeadlessMain.scala` — R2(b) resolver call sites (only if path b).
- Tests needing fixture-name updates for R1: `MetaTest`, `TwistTest`, `DraftTest`, `EnvRulesTest`, `BranchTest`, `JournalTest`, `ConfigCodecTest`, `ConfigTest`.

Verify
------
1. `sbt compile`.
2. `sbt test` — full suite incl. the existing `LoadoutTest` and the R1
   name-fixture updates.
3. Visual (`sbt "game/run"`, needs display): menu PLAY/TUTORIAL/TALENTS;
   PLAY -> briefing (biome, env rule, resist theme, twists — no exact %) ->
   loadout (cap enforced) -> play; TUTORIAL -> briefing -> play; TALENTS ->
   spend currency, prereq gating, active-effects summary. For R2: tutorial is
   skippable from briefing + pause.
4. Briefing never reveals discoverable exacts (only kinds/rules, telegraphed).
5. `ConfigWriterTest` round-trip stays green after the R1 config rename.

Notes / gotchas (for whoever finishes R1/R2)
--------------------------------------------
- RENAME ONLY `name`/`targetTower`/`roster`/`composition`, NEVER ids. ids are
  the stable keys (`tutorial`, `cube-farm`, `pyromancer`, `square-deal`, ...);
  renaming them breaks talent prereqs, relic gating, and the journal.
- Keep `ConfigWriter` in sync with `GameConfig` (only matters if R2(b) adds a
  `tutorialLevels` field) — `ConfigWriterTest` round-trips `Config.game`.
- `Ui` rects must be `def`s (read `Config.WorldW` at call time), not `val`s.
- The hidden `T` tutorial hotkey (`Main.scala`) coexists with the TUTORIAL
  button — keep both.
- For R1, the enemy names are already done; do not "fix" them and break the
  cross-references that now expect the themed enemy names.
- The loadout `unlocked`/`locked` lists must cover EVERY tower; if R1 renames
  towers, update the loadout `unlocked`/`locked`/`defaultSelected` entries too
  (they reference tower `name`s).
