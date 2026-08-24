package td

class BranchTest extends TestBase:
  // Config/Grid resolve board/levels off a global active-biome singleton; reset
  // it so a prior suite's run can't leak its biome into this suite's reads.
  // Ashen Ridge (level 1) is the single branched route in the bundled config.
  private val BranchLevel = 1

  // The tutorial now has its own (small, unbranched) map, so branch tests use a
  // real biome that falls back to the global branched levels, with no environment
  // rule (which would grant non-deterministic armor) and pre-rolled lane choices
  // so non-specialists actually split at the fork.
  private def branchRun: RunContext =
    val b = Config.biome("front-lawn")
    RunContext(
      seed = 1L,
      biome = b.id,
      resistTheme = b.resistTheme,
      roster = b.roster,
      laneChoices = (0 until 64).map(i => if i % 2 == 0 then 0L else 1L).toVector
    )

  private def enemy(tpe: String, id: Long, progress: Float, speed: Float = 1000f): Enemy =
    val spec = Config.enemySpec(tpe)
    Enemy(id = id, progress = progress, hp = 1e6f, maxHp = 1e6f, speed = speed,
      tpe = tpe, level = BranchLevel, resist = spec.resist)

  private def world(run: RunContext, enemies: Vector[Enemy]): World =
    Sim.initial(run).copy(level = BranchLevel, enemies = enemies, spawnQueue = Vector.empty, breakTimer = 999f)

  // -- config ----------------------------------------------------------------

  test("bundled config has a branched route with a side-taking specialist") {
    val route = Config.levelAt(BranchLevel).route
    assert(route.branches.nonEmpty)
    assertEquals(route.branches.head.side, RouteSide.Right)
    assertEquals(Config.enemySpec("Edgelord").sidePreference, Some(RouteSide.Left))
    assert(Config.enemySpec("Cardboard").sidePreference.isEmpty)
  }

  test("branch with an out-of-range fork index is rejected") {
    val json = routeJson("""[ { "from": 5, "to": 1, "side": "Left", "waypoints": [[1, 1]] } ]""")
    assert(ConfigLoader.parse(json).left.exists(_.contains("out of range")))
  }

  test("two branches at the same fork are rejected") {
    val json = routeJson(
      """[ { "from": 0, "to": 1, "side": "Left", "waypoints": [[1, 0]] },
        |  { "from": 0, "to": 1, "side": "Right", "waypoints": [[1, 0]] } ]""".stripMargin
    )
    assert(ConfigLoader.parse(json).left.exists(_.contains("multiple branches")))
  }

  // -- grid derivation -------------------------------------------------------

  test("fork and merge map continuously: branch meets trunk at both ends") {
    val fork = Grid.forks(BranchLevel).head
    assertEquals(Grid.positionAt(fork.forkProgress, BranchLevel, Some(fork.branchIndex)),
      Grid.positionAt(fork.forkProgress, BranchLevel, None))
    assertEquals(Grid.positionAt(fork.mergeProgress, BranchLevel, Some(fork.branchIndex)),
      Grid.positionAt(fork.mergeProgress, BranchLevel, None))
  }

  test("branch cells are folded into the buildable-path set") {
    val path = Grid.pathCells(BranchLevel)
    assert(path.contains(GridPos(8, 17)))  // trunk
    assert(path.contains(GridPos(8, 3)))   // branch corner
    assert(path.contains(GridPos(12, 3)))  // along the branch
    assert(!Grid.isBuildable(GridPos(8, 3), BranchLevel))
  }

  test("a branch-lane enemy is not on the trunk mid-fork") {
    val fork = Grid.forks(BranchLevel).head
    val mid  = (fork.forkProgress + fork.mergeProgress) / 2f
    assertNotEquals(Grid.positionAt(mid, BranchLevel, Some(fork.branchIndex)),
      Grid.positionAt(mid, BranchLevel, None))
  }

  // -- movement --------------------------------------------------------------

  test("Edgelord always takes the left fork (the trunk, opposite the right branch)") {
    val run = branchRun
    val fork = Grid.forks(BranchLevel).head
    val start = fork.forkProgress - 0.5f
    val ws = world(run, (1 to 5).map(i => enemy("Edgelord", i.toLong, start)).toVector)
    val stepped = Sim.step(ws, Config.MaxStepDt, Vector.empty)
    assertEquals(stepped.enemies.size, 5)
    assert(stepped.enemies.forall(_.lane.isEmpty), "Edgelord must stay on the trunk/left lane")
  }

  test("non-specialist enemies split across the two lanes at the fork") {
    val run = branchRun
    val fork = Grid.forks(BranchLevel).head
    val start = fork.forkProgress - 0.5f
    val ws = world(run, (1 to 40).map(i => enemy("Cardboard", i.toLong, start)).toVector)
    val stepped = Sim.step(ws, Config.MaxStepDt, Vector.empty)
    val lanes = stepped.enemies.map(_.lane).toSet
    assert(lanes.contains(None), s"expected some enemies on the trunk, got $lanes")
    assert(lanes.contains(Some(fork.branchIndex)), s"expected some enemies on the branch, got $lanes")
  }

  test("movement is deterministic: same seed -> identical lanes") {
    val run = branchRun
    val fork = Grid.forks(BranchLevel).head
    val start = fork.forkProgress - 0.5f
    val ws = world(run, (1 to 20).map(i => enemy("Cardboard", i.toLong, start)).toVector)
    val a = Sim.step(ws, Config.MaxStepDt, Vector.empty)
    val b = Sim.step(ws, Config.MaxStepDt, Vector.empty)
    assertEquals(a, b)
  }

  test("no enemy teleports through a branch: position stays near the fork just after it") {
    val fork = Grid.forks(BranchLevel).head
    val justAfter = fork.forkProgress + 1f
    val pos = Grid.positionAt(justAfter, BranchLevel, Some(fork.branchIndex))
    val (fx, fy) = Grid.positionAt(fork.forkProgress, BranchLevel, Some(fork.branchIndex))
    val d = math.hypot(pos._1 - fx, pos._2 - fy).toFloat
    assert(d < Config.CellPx * 2f, s"enemy jumped $d world units at the fork")
  }

  // -- journal ---------------------------------------------------------------

  test("side-taking preference is discovered: Sighting then Studied after N observations") {
    val run = branchRun
    val fork = Grid.forks(BranchLevel).head
    val start = fork.forkProgress - 0.5f
    val n = Config.StudyHits
    val ws = world(run, (1 to n).map(i => enemy("Edgelord", i.toLong, start)).toVector)
    val stepped = Sim.step(ws, Config.MaxStepDt, Vector.empty)
    val route = stepped.events.filter(e => e.enemy == "Edgelord" && e.traitKey == "route")
    assert(route.exists(e => e.tier == Precision.Sighting && e.value == "takes a side"))
    assert(route.exists(e => e.tier == Precision.Studied && e.value == "left"))
  }

  // -- helpers ---------------------------------------------------------------

  private def routeJson(branches: String): String =
    s"""{
  "board": { "gridSize": 20, "cellPx": 32.0, "panelPx": 240.0 },
  "economy": { "startGold": 120, "startLives": 20, "killReward": 8, "levelBonus": 50 },
  "meta": { "payoutPerLevel": 10, "payoutPerWave": 2, "payoutWinBonus": 25 },
  "draft": { "offerCount": 3, "rerollCost": 25, "rerollCostGrowth": 15, "poolRounds": 10 },
  "combat": { "maxStepDt": 0.05, "fastForward": 3, "hitRadius": 6.0, "burnDps": 6.0, "burnDuration": 3.0, "slowFactor": 0.5, "slowDuration": 2.0, "breakTime": 4.0 },
  "i18n": { "lang": "en", "showCodes": false },
  "towers": [],
  "loadout": { "maxSelected": 3, "defaultSelected": [], "unlocked": [], "locked": [] },
  "enemies": [
    { "name": "Cardboard", "baseHp": 22.0, "baseSpeed": 42.0, "color": "#ED7477", "size": 16.0,
      "resist": { "kinetic": 0.0, "fire": 0.0, "slow": 0.0, "splash": 0.0, "explosion": 0.0 }, "aura": null }
  ],
  "levels": [
    { "name": "L1", "route": { "waypoints": [[0, 0], [1, 0], [2, 0]], "branches": $branches },
      "waves": [ { "count": 1, "spawnInterval": 1.0, "hpScale": 1.0, "composition": { "Cardboard": 1 } } ] }
  ],
  "colors": {
    "background": "#021A01", "path": "#328A2E", "build": "#12520F", "gridLine": "#00000040", "enemy": "#ED7477",
    "shot": "#FFE6A5", "shotFire": "#ED7477", "shotFrost": "#9CA2E1", "shotSplash": "#5C63A5", "shotBoom": "#FEA4A6",
    "burnTint": "#ED7477", "slowTint": "#9CA2E1", "buffTint": "#9DEC99", "panel": "#030516", "hoverOk": "#64C15F99",
    "hoverBad": "#ED747780", "range": "#9CA2E166", "buttonFill": "#323875", "hpBarBack": "#030516", "hpBarFill": "#9DEC99",
    "text": "#FFE6A5", "dimText": "#5C63A5"
  },
  "biomes": [ { "id": "tutorial", "name": "T", "roster": ["Cardboard"], "environment": null,
      "resistTheme": { "primary": "Kinetic", "primaryAmount": 0.0, "secondary": "Fire", "secondaryAmount": 0.0 } } ],
  "twists": [],
  "relics": [],
  "augments": [],
  "talents": []
}"""
