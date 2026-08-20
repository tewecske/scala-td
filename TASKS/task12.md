TASK 12 — BRANCHES + SIDE-TAKING ENEMIES
========================================

Goal
----
Allow routes to fork into branches. Enemies traverse branches randomly; add
the specialist enemy type that always takes a specific side, whose route
preference is a discoverable journal trait.

Depends on
----------
task06 (sim movement), task07 (journal traits), task02 (run context).

Plan refs
---------
PLAN section 11 and section 13 note (the left-fork enemy).

Steps
-----
1. Extend the route model (Grid + GameConfig Route) so a route can branch and
   re-merge. Path cells and the walkable polyline(s) stay DERIVED from
   waypoints (AGENTS.md gotcha — don't hand-author derived data).
2. Update movement: an enemy chooses a branch (random by default) at a fork
   and continues along that lane; "furthest along" progress still works.
3. Add the side-taking specialist (e.g. Edgelord always takes the left fork).
   Encode side-preference as a data flag on the enemy spec.
4. Record route preference as a discoverable journal trait (task07 model):
   first sighting vague, later "always takes the left branch".
5. Keep targeting/towers working on branched routes (range, splash) and
   branch-aware leaks counting toward lives.

Files
-----
- core/src/main/scala/td/Grid.scala (branch derivation + positionAt)
- core/src/main/scala/td/GameConfig.scala (Route branch schema)
- core/src/main/scala/td/ConfigLoader.scala (parse branches)
- core/src/main/scala/td/Sim.scala (fork choice + side-taking)
- core/src/main/scala/td/Model.scala (branch state on Enemy)
- core/src/main/resources/config.json (a branched route + the specialist)

Verify
------
- `sbt compile`.
- Headless: enemies split across branches at a fork; the specialist always
  takes the left side; journal records its preference after N observations.
- No enemy teleports through a branch (dt clamp still holds).

Notes
-----
- Start with ONE branched route; don't over-engineer graph routing yet.
- This is the feature remembered in PLAN section 13.
