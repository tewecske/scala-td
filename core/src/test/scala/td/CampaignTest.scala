package td

class CampaignTest extends TestBase:

  private def campaign = Config.game.levels

  test("the campaign has ten levels with waves growing every two levels") {
    assertEquals(campaign.size, 10)
    assertEquals(campaign.map(_.waves.size), Vector(3, 3, 4, 4, 5, 5, 6, 6, 7, 7))
  }

  test("every level's last wave holds exactly one boss") {
    campaign.foreach { lvl =>
      val bosses = lvl.waves.last.composition.filter { case (n, _) => Config.enemySpec(n).boss }
      assertEquals(bosses.map(_._2).sum, 1, s"${lvl.name}")
    }
  }

  test("every enemy promotion points at a real enemy type") {
    Config.game.enemies.foreach { e =>
      e.promotesTo.foreach(t => assert(Config.game.enemies.exists(_.name == t), s"${e.name} -> $t"))
    }
  }

  test("the board is always the same window size, however big the grid") {
    campaign.indices.foreach { i =>
      Config.withContext("front-lawn", i) {
        assertEqualsDouble(Config.GridSize * Config.CellPx, Config.BoardPx.toDouble, 1e-3)
      }
    }
  }

  test("grid sizes change by level: small maps first, then 14x14") {
    val sizes = campaign.indices.map(i => Config.gridSizeOf(i))
    assertEquals(sizes.take(5).toSet, Set(10))
    assertEquals(sizes.drop(5).toSet, Set(14))
  }

  test("every route stays on its level's grid") {
    campaign.indices.foreach { i =>
      val size = Config.gridSizeOf(i)
      campaign(i).route.waypoints.foreach { p =>
        assert(p.x >= 0 && p.x < size && p.y >= 0 && p.y < size, s"${campaign(i).name} $p outside ${size}x$size")
      }
    }
  }

  test("a shock hit does more damage to a weak enemy than to one with no shock weakness") {
    def hitOn(tpe: String): Float =
      val e    = Enemy(id = 1L, progress = 0f, hp = 1000f, maxHp = 1000f, speed = 0f, tpe = tpe, level = 0,
        resist = Config.enemySpec(tpe).resist)
      val (x, y) = e.pos
      val shot = Projectile(x, y, e.id, DamageKind.Shock, 10f, 1e6f, 0f, None)
      val w = Sim.initial(RunContext(seed = 1L)).copy(enemies = Vector(e), shots = Vector(shot),
        spawnQueue = Vector.empty, breakTimer = 999f)
      1000f - Sim.step(w, Config.MaxStepDt, Vector.empty).enemies.head.hp
    assert(hitOn("Drip") > hitOn("Cardboard"))
    assertEqualsDouble(hitOn("Drip").toDouble, 16.0, 1e-3)
  }

  test("a crate captain leaves three splinters behind where it dies") {
    val e = Enemy(id = 1L, progress = 40f, hp = 1f, maxHp = 130f, speed = 0f, tpe = "Crate Captain", level = 0,
      resist = Config.enemySpec("Crate Captain").resist)
    val (x, y) = e.pos
    val shot = Projectile(x, y, e.id, DamageKind.Kinetic, 50f, 1e6f, 0f, None)
    val w = Sim.initial(RunContext(seed = 1L)).copy(enemies = Vector(e), shots = Vector(shot),
      spawnQueue = Vector.empty, breakTimer = 999f)
    val after = Sim.step(w, Config.MaxStepDt, Vector.empty)
    val splinters = after.enemies.filter(_.tpe == "Splinter")
    assertEquals(splinters.size, 3)
    assert(splinters.forall(_.progress == 40f))
  }

  test("a chill aura slows the reload of towers within its reach") {
    val chiller = Enemy(id = 1L, progress = 0f, hp = 100f, maxHp = 100f, speed = 0f, tpe = "Ice Captain", level = 0,
      resist = Config.enemySpec("Ice Captain").resist)
    val (cx, cy) = chiller.pos
    val tower = Tower(cellNear(Grid.cellAt(cx, cy)), 1.0f, "Cuboid")
    val chilled = Sim.step(
      Sim.initial(RunContext(seed = 1L)).copy(towers = Vector(tower), enemies = Vector(chiller),
        spawnQueue = Vector.empty, breakTimer = 999f),
      Config.MaxStepDt, Vector.empty)
    val plain = Sim.step(
      Sim.initial(RunContext(seed = 1L)).copy(towers = Vector(tower), enemies = Vector.empty,
        spawnQueue = Vector.empty, breakTimer = 999f),
      Config.MaxStepDt, Vector.empty)
    assert(chilled.towers.head.cooldown > plain.towers.head.cooldown)
  }

  test("each level starts with its own gold; leftover gold does not carry over") {
    val w0 = Sim.initial(RunContext(seed = 1L))
    assertEquals(w0.gold, Config.levelStartGold(0))
    val relic = Config.relics.head.id
    val rich  = w0.copy(gold = 9999, draft = Some(Draft(Vector(DraftOffer.Relic(relic)), Config.RerollCost)))
    val next  = Sim.step(rich, Config.MaxStepDt, Vector(Command.PickRelic(relic)))
    assertEquals(next.level, 1)
    assertEquals(next.gold, Config.levelStartGold(1))
  }

  test("every level's first wave is its smallest and waves never shrink") {
    campaign.foreach { l =>
      val counts = l.waves.map(_.count)
      assertEquals(counts, counts.sorted, l.name)
      assert(counts.head < counts.last, l.name)
    }
  }

  /** One enemy of `tpe` about to reach the exit on level 1 of the main campaign. */
  private def leakOf(tpe: String, talents: Vector[String] = Vector.empty): World =
    val run  = RunContext(seed = 3L, talents = talents)
    val spec = Config.enemySpec(tpe)
    val len  = Config.withContext(run.biome, 0)(Grid.length(0))
    val e    = Enemy(id = 1L, progress = len - 1f, hp = spec.baseHp, maxHp = spec.baseHp, speed = 100f,
      tpe = tpe, level = 0, resist = spec.resist)
    val w = Sim.initial(run).copy(enemies = Vector(e), spawnQueue = Vector.empty, breakTimer = 999f)
    Sim.step(w, Config.MaxStepDt, Vector.empty)

  test("bigger units cost more lives when they leak: base 1, veteran 2, captain 3, boss 4") {
    val start = Config.StartLives
    assertEquals(start - leakOf("Crate").lives, 1)
    assertEquals(start - leakOf("Pallet").lives, 2)
    assertEquals(start - leakOf("Crate Captain").lives, 3)
    assertEquals(leakOf("The Trespasser").lives, math.max(0, start - 4))
  }

  test("life talents add starting lives, and lives never reset between levels") {
    assertEquals(Sim.initial(RunContext(seed = 1L, talents = Vector("filler-lives"))).lives, Config.StartLives + 1)
    assertEquals(Sim.initial(RunContext(seed = 1L, talents = Vector("filler-lives", "reinforced-core"))).lives,
      Config.StartLives + 3)
    val relic = Config.relics.head.id
    val hurt  = Sim.initial(RunContext(seed = 1L)).copy(lives = 2,
      draft = Some(Draft(Vector(DraftOffer.Relic(relic)), Config.RerollCost)))
    assertEquals(Sim.step(hurt, Config.MaxStepDt, Vector(Command.PickRelic(relic))).lives, 2)
  }
