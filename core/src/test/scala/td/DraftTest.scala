package td

class DraftTest extends TestBase:

  private def tutorialRun(talents: Vector[String] = Vector.empty): RunContext =
    RunGen.tutorial(1L, talents)

  /** A level-0 world on its last wave with an empty field, so a single step
    * triggers the end-of-level transition into the draft.
    */
  private def atLastWave(talents: Vector[String] = Vector.empty): World =
    Sim.initial(tutorialRun(talents)).copy(
      wave = Waves.waveCount(0),
      enemies = Vector.empty,
      spawnQueue = Vector.empty,
      spawnTimer = 0f,
      breakTimer = 0f
    )

  test("eligibleRelics gates by deep talent") {
    val shallow = DraftGen.eligibleRelics(Vector.empty)
    assert(shallow.nonEmpty)
    assert(shallow.forall(_.rarity == Rarity.Common))
    val deep = DraftGen.eligibleRelics(Vector("conflagration"))
    assert(deep.exists(r => r.id == "pandoras-box" && r.rarity == Rarity.Legendary))
    assert(deep.exists(r => r.id == "box-office" && r.rarity == Rarity.Rare))
  }

  test("draft pool is deterministic and shallow pools have no legendaries") {
    val a = DraftGen.rollPool(Rng(42L), Vector.empty)
    val b = DraftGen.rollPool(Rng(42L), Vector.empty)
    assertEquals(a, b)
    val relicRarities = a.collect { case DraftOffer.Relic(id) => Config.relic(id).rarity }
    assert(relicRarities.forall(_ == Rarity.Common))
  }

  test("completing a level opens a draft") {
    val drafted = Sim.step(atLastWave(), Config.MaxStepDt, Vector.empty)
    assert(drafted.draft.isDefined)
    assertEquals(drafted.draft.get.offers.size, Config.OfferCount)
  }

  test("reroll unavailable without the reroll talent") {
    val drafted = Sim.step(atLastWave(), Config.MaxStepDt, Vector.empty)
    val after   = Sim.step(drafted, Config.MaxStepDt, Vector(Command.Reroll()))
    assertEquals(after, drafted)
  }

  test("reroll spends gold and rerolls with the talent") {
    val drafted = Sim.step(atLastWave(Vector("reroll")), Config.MaxStepDt, Vector.empty)
    assert(drafted.draft.isDefined)
    val after = Sim.step(drafted, Config.MaxStepDt, Vector(Command.Reroll()))
    assertEquals(after.gold, drafted.gold - Config.RerollCost)
    assertEquals(after.draftCursor, drafted.draftCursor + Config.OfferCount)
    assertEquals(after.draft.get.rerollCost, drafted.draft.get.rerollCost + Config.RerollCostGrowth)
  }

  test("picking a relic applies it globally and advances the level") {
    val base = Sim.initial(tutorialRun()).copy(
      draft = Some(Draft(Vector(DraftOffer.Relic("square-deal")), Config.RerollCost)),
      draftCursor = Config.OfferCount
    )
    val after = Sim.step(base, Config.MaxStepDt, Vector(Command.PickRelic("square-deal")))
    assertEquals(after.run.relics, Vector("square-deal"))
    assertEquals(after.draft, None)
    assertEquals(after.level, 1)
  }

  test("picking an augment holds it pending") {
    val base = Sim.initial(tutorialRun()).copy(
      draft = Some(Draft(Vector(DraftOffer.Augment("vertigo")), Config.RerollCost)),
      draftCursor = Config.OfferCount
    )
    val after = Sim.step(base, Config.MaxStepDt, Vector(Command.PickAugment("vertigo")))
    assertEquals(after.run.augments, Vector("vertigo"))
    assertEquals(after.pendingAugment, Some("vertigo"))
    assertEquals(after.draft, None)
    assertEquals(after.level, 1)
  }

  test("augment attaches to exactly one tower; second socket refused") {
    val cell = GridPos(5, 5)
    val base = Sim.initial(tutorialRun()).copy(
      towers = Vector(Tower(cell, 0f, "Cuboid")),
      pendingAugment = Some("sharpened-edge"),
      breakTimer = 999f,
      spawnQueue = Vector.empty,
      enemies = Vector.empty
    )
    val after = Sim.step(base, Config.MaxStepDt, Vector(Command.AttachAugment(cell)))
    assertEquals(after.towers.head.augment, Some("sharpened-edge"))
    assertEquals(after.pendingAugment, None)

    val second = after.copy(pendingAugment = Some("vertigo"))
    val after2 = Sim.step(second, Config.MaxStepDt, Vector(Command.AttachAugment(cell)))
    assertEquals(after2.towers.head.augment, Some("sharpened-edge"))
    assertEquals(after2.pendingAugment, Some("vertigo"))
  }

  test("\"reroll\" talent sets rerollUnlocked") {
    assert(Meta.effects(Vector("reroll")).rerollUnlocked)
    assert(!Meta.effects(Vector.empty).rerollUnlocked)
  }

  test("the draft never offers what the run already owns") {
    val w      = atLastWave()
    val round  = w.run.draftPool.slice(w.draftCursor, w.draftCursor + Config.OfferCount)
    val owned  = round.head
    val run    = owned match
      case DraftOffer.Relic(id)   => w.run.copy(relics = Vector(id))
      case DraftOffer.Augment(id) => w.run.copy(augments = Vector(id))
    val drafted = Sim.step(w.copy(run = run), Config.MaxStepDt, Vector.empty)
    assert(!drafted.draft.get.offers.contains(owned))
    assertEquals(drafted.draft.get.offers.size, round.distinct.size - 1)
  }

  test("a draft with nothing left to offer is skipped instead of soft-locking") {
    val w     = atLastWave()
    val round = w.run.draftPool.slice(w.draftCursor, w.draftCursor + Config.OfferCount)
    val run   = w.run.copy(
      relics = round.collect { case DraftOffer.Relic(id) => id },
      augments = round.collect { case DraftOffer.Augment(id) => id })
    val next = Sim.step(w.copy(run = run), Config.MaxStepDt, Vector.empty)
    assert(next.draft.isEmpty)
    assertEquals(next.level, 1)
    assertEquals(next.gold, Config.withBiome(w.run.biome)(Config.levelStartGold(1)))
  }
