package td

import java.nio.file.Files

class MetaTest extends TestBase:
  private def tmpPath(suffix: String): String =
    Files.createTempFile(s"meta-$suffix", ".json").toString

  private def tutorialRun(talents: Vector[String] = Vector.empty): World =
    Sim.initial(RunGen.tutorial(1L).copy(talents = talents))

  test("missing meta.json loads as empty") {
    val path = tmpPath("missing")
    new java.io.File(path).delete()
    assertEquals(Meta.load(path), Right(MetaData.empty))
  }

  test("save/load round-trips balance and talents") {
    val path = tmpPath("roundtrip")
    val data = MetaData(balance = 42, talents = Vector("filler-damage", "pyromancer"))
    Meta.save(path, data)
    assertEquals(Meta.load(path), Right(data))
  }

  test("payout scales with progress and awards a win bonus") {
    val lost = tutorialRun().copy(level = 1, wave = 3)
    assertEquals(Meta.payout(lost), 1 * Config.PayoutPerLevel + 3 * Config.PayoutPerWave)
    val won = tutorialRun().copy(won = true, level = Config.levelCount, wave = 4)
    assertEquals(
      Meta.payout(won),
      Config.levelCount * Config.PayoutPerLevel + 4 * Config.PayoutPerWave + Config.PayoutWinBonus
    )
  }

  test("losing a run banks partial currency") {
    var w = Sim.initial(RunGen.roll(1L))
    var guard = 0
    while !w.isOver && guard < 200000 do
      w = Sim.step(w, Config.MaxStepDt, Vector.empty)
      guard += 1
    assert(w.isOver)
    assert(Meta.bank(w, MetaData.empty).balance > 0)
  }

  test("core purchase requires its filler prereq") {
    assert(Meta.purchase(MetaData.empty, "pyromancer").isLeft)
    val afterFiller = Meta.purchase(MetaData(balance = 10, talents = Vector.empty), "filler-damage").toOption.get
    assert(Meta.purchase(afterFiller, "pyromancer").isRight)
  }

  test("purchase deducts cost and rejects duplicates") {
    val r = Meta.purchase(MetaData(balance = 10, talents = Vector.empty), "filler-damage").toOption.get
    assertEquals(r.balance, 9)
    assertEquals(r.talents, Vector("filler-damage"))
    assert(Meta.purchase(r, "filler-damage").isLeft)
  }

  test("multiple cores can be active at once") {
    val d0 = MetaData(balance = 10, talents = Vector.empty)
    val d1 = Meta.purchase(d0, "filler-damage").toOption.get
    val d2 = Meta.purchase(d1, "pyromancer").toOption.get
    val d3 = Meta.purchase(d2, "breaker").toOption.get
    val eff = Meta.effects(d3.talents)
    assert(eff.activeCores.contains("pyromancer"))
    assert(eff.activeCores.contains("breaker"))
  }

  test("effects aggregates filler bonuses and leaves cores stat-neutral") {
    val eff = Meta.effects(Vector("filler-damage", "filler-range", "filler-gold"))
    assertEquals(eff.damageMult, 1.1f)
    assertEquals(eff.rangeMult, 1.1f)
    assertEquals(eff.goldMult, 1.15f)
    val core = Meta.effects(Vector("pyromancer"))
    assertEquals(core.damageMult, 1f)
    assert(core.owns("pyromancer"))
    assert(core.activeCores.contains("pyromancer"))
  }

  test("damageBonus multiplies tower shot damage") {
    def shotDamage(talents: Vector[String]): Float =
      val run = RunContext(seed = 1L, talents = talents)
      val e = Enemy(id = 1L, progress = 50f, hp = 100f, maxHp = 100f, speed = 0f,
        tpe = "Cardboard", level = 0, resist = Config.enemySpec("Cardboard").resist)
      val (ex, ey) = e.pos
      val w = Sim.initial(run).copy(
        towers = Vector(Tower(cellNear(Grid.cellAt(ex, ey)), 0f, "Cuboid")),
        enemies = Vector(e),
        spawnQueue = Vector.empty,
        breakTimer = 999f
      )
      Sim.step(w, Config.MaxStepDt, Vector.empty).shots.head.damage
    assertEquals(shotDamage(Vector.empty), 12f)
    assertEquals(shotDamage(Vector("filler-damage")), 12f * 1.1f)
  }

  test("goldBonus increases kill reward") {
    def goldAfterKill(talents: Vector[String]): Int =
      val run = RunContext(seed = 1L, talents = talents)
      val e = Enemy(id = 1L, progress = 0f, hp = 1f, maxHp = 100f, speed = 0f,
        tpe = "Cardboard", level = 0, resist = Config.enemySpec("Cardboard").resist)
      val (ex, ey) = e.pos
      val p = Projectile(ex, ey, 1L, DamageKind.Kinetic, 1f, 1e6f, 0f, None)
      val w = Sim.initial(run).copy(enemies = Vector(e), shots = Vector(p),
        spawnQueue = Vector.empty, breakTimer = 999f)
      Sim.step(w, Config.MaxStepDt, Vector.empty).gold
    assertEquals(goldAfterKill(Vector.empty) - Config.StartGold, Config.KillReward)
    assertEquals(goldAfterKill(Vector("filler-gold")) - Config.StartGold, math.round(Config.KillReward * 1.15f))
  }

  test("save/load round-trips bought towers") {
    val path = tmpPath("towers")
    val data = MetaData(balance = 5, talents = Vector.empty, towers = Vector("Static Box"))
    Meta.save(path, data)
    assertEquals(Meta.load(path), Right(data))
  }

  test("a locked tower is bought with meta currency and joins the unlocked set") {
    val locked = Config.Loadout.locked.head
    val cost   = Config.Loadout.unlockCosts(locked)
    assert(!Meta.unlockedTowers(MetaData.empty).contains(locked))
    val ready = MetaData(cost, Vector.empty, bestCleared = Config.Loadout.levelsToUnlock(locked))
    val next = Meta.purchaseTower(ready, locked).toOption.get
    assertEquals(next.balance, 0)
    assert(Meta.unlockedTowers(next).contains(locked))
  }

  test("tower purchase rejects short balance, duplicates and starting towers") {
    val locked = Config.Loadout.locked.head
    val cost   = Config.Loadout.unlockCosts(locked)
    assert(Meta.purchaseTower(MetaData(cost - 1, Vector.empty, bestCleared = 99), locked).isLeft)
    val owned = MetaData(cost * 2, Vector.empty, Vector(locked))
    assert(Meta.purchaseTower(owned, locked).isLeft)
    assert(Meta.purchaseTower(owned, Config.Loadout.unlocked.head).isLeft)
  }

  test("every locked tower in the bundled config has a price") {
    Config.Loadout.locked.foreach(t => assert(Config.Loadout.unlockCosts.contains(t), t))
  }

class TowerUnlockGateTest extends TestBase:

  test("a locked tower can only be bought once the player's best run cleared its level") {
    val tower = "Matchbox"
    val needs = Config.Loadout.levelsToUnlock(tower)
    assert(needs > 0)
    val rich  = MetaData(balance = 999, talents = Vector.empty)
    assert(Meta.purchaseTower(rich.copy(bestCleared = needs - 1), tower).left.exists(_.contains(s"clear level $needs")))
    assert(Meta.purchaseTower(rich.copy(bestCleared = needs), tower).exists(_.towers.contains(tower)))
    assert(Meta.purchaseTower(MetaData(0, Vector.empty, bestCleared = needs), tower).isLeft, "still costs currency")
  }

  test("a finished run raises the best levels cleared, never lowers it, and the save keeps it") {
    val lostOnThird = Sim.initial(RunContext(seed = 1L)).copy(level = 2, lives = 0)
    assertEquals(Meta.bank(lostOnThird, MetaData.empty).bestCleared, 2)
    assertEquals(Meta.bank(lostOnThird, MetaData(0, Vector.empty, bestCleared = 4)).bestCleared, 4)
    val path = java.nio.file.Files.createTempFile("meta-best", ".json").toString
    Meta.save(path, MetaData(3, Vector("filler-damage"), Vector("Ice Cube"), bestCleared = 3))
    assertEquals(Meta.load(path).map(_.bestCleared), Right(3))
  }

  test("unlock levels survive the config writer and only name locked towers") {
    val back = ConfigLoader.parse(ConfigWriter.toJson(Config.game)).fold(e => fail(e), identity)
    assertEquals(back.loadout.unlockLevels, Config.Loadout.unlockLevels)
    val bad = ConfigWriter.toJson(Config.game).replace("\"unlockLevels\": {", "\"unlockLevels\": { \"Cuboid\": 2,")
    assert(ConfigLoader.parse(bad).left.exists(_.contains("unlockLevels")))
  }
