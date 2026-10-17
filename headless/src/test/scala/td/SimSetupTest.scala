package td

class SimSetupTest extends munit.FunSuite:
  private val cfg = ConfigLoader.bundled().fold(e => throw RuntimeException(e), identity)

  override def beforeEach(context: BeforeEach): Unit =
    Config.use(cfg)
    Config.clearActiveBiome()

  test("a setup survives a save and load") {
    val s = SimSetup(name = "mid run", config = "x.json", bot = "spender", biome = "cube-farm", level = 3, games = 7,
      seed = 9L, think = 3, livesLost = 2, unlocked = Vector("Cuboid", "Matchbox"), everyLoadout = false,
      talents = Vector("breaker"), relics = Vector(cfg.relics.head.id), augment = Some(cfg.augments.head.id))
    assertEquals(SimSetup.parse(SimSetup.toJson(s)), Right(s))
    assertEquals(SimSetup.parse(SimSetup.toJson(SimSetup())), Right(SimSetup()))
  }

  test("owned talents bring their prerequisites, prerequisites first") {
    assertEquals(SimSetup.withPrereqs(cfg, Vector("sharp-corners")), Vector("filler-crit", "sharp-corners"))
  }

  test("more unlocked towers than a run brings: every loadout of the allowed size, or all of them") {
    val max = cfg.loadout.maxSelected
    val all = cfg.towers.map(_.name)
    val every = SimSetup.loadouts(cfg, SimSetup(unlocked = all))
    assertEquals(every.size, all.combinations(max).size)
    assert(every.forall(_.size == max))
    assertEquals(SimSetup.loadouts(cfg, SimSetup(unlocked = all, everyLoadout = false)), Vector(all))
    assertEquals(SimSetup.loadouts(cfg, SimSetup()), Vector(cfg.loadout.defaultSelected))
  }

  test("a setup plays its games from the chosen level with what the player has") {
    val s = SimSetup(bot = "builder", biome = "cube-farm", level = 1, games = 2, unlocked = Vector("Cuboid", "Matchbox"),
      talents = Vector("filler-rate"))
    assertEquals(SimSetup.problems(cfg, s), Vector.empty)
    var last = (0, 0)
    val rs = SimSetup.run(cfg, s, progress = (d, t) => last = (d, t))
    assertEquals(rs.map(_.loadout), Vector(Vector("Cuboid", "Matchbox")))
    assertEquals(rs.head.stats.games, 2)
    assertEquals(last, (2, 2))
    assert(SimSetup.problems(cfg, s.copy(bot = "nope", level = 99, unlocked = Vector("Nope"))).size == 3)
  }

  test("the builder only builds towers its loadout allows") {
    val s  = SimSetup(bot = "builder", biome = "cube-farm", level = 2, games = 2, unlocked = Vector("Matchbox", "Ice Cube"))
    val rs = SimSetup.run(cfg, s)
    assert(rs.head.stats.towersByType.keySet.subsetOf(Set("Matchbox", "Ice Cube")), rs.head.stats.towersByType)
    assert(rs.head.stats.towers > 0)
  }
