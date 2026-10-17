package td

class ConfigEditsTest extends munit.FunSuite:
  private def cfg = ConfigLoader.bundled().toOption.get

  test("renaming an enemy renames it in waves, ladders and rosters, and the config still loads") {
    val c0   = cfg
    val name = "Crate"
    assert(ConfigEdits.uses(c0, name).nonEmpty)
    val c1 = ConfigEdits.renameEnemy(c0, name, "Shoebox")
    assert(!c1.enemies.exists(_.name == name))
    assert(ConfigEdits.uses(c1, name).isEmpty)
    assertEquals(ConfigEdits.uses(c1, "Shoebox").size, ConfigEdits.uses(c0, name).size)
    assert(ConfigLoader.parse(ConfigWriter.toJson(c1)).isRight)
  }

  test("an enemy in use cannot be deleted; an unused one can") {
    val c0 = cfg
    assert(ConfigEdits.deleteEnemy(c0, "Crate").isLeft)
    val spare = ConfigEdits.freshName(c0, "Spare")
    val c1 = c0.copy(enemies = c0.enemies :+ c0.enemies.head.copy(name = spare))
    assertEquals(ConfigEdits.deleteEnemy(c1, spare).map(_.enemies.size), Right(c0.enemies.size))
  }

  test("edited waves keep their count equal to the composition, so the game accepts them") {
    val c0 = cfg
    val c1 = ConfigEdits.updateLevel(c0, ConfigEdits.LevelSet.Campaign, 0) { l =>
      l.copy(waves = l.waves :+ ConfigEdits.wave(0.8f, 1.2f, Vector("Cardboard" -> 3, "Crate" -> 0, "Paper Cut" -> 2)))
    }
    val added = c1.levels.head.waves.last
    assertEquals(added.count, 5)
    assertEquals(added.composition.map(_._1), Vector("Cardboard", "Paper Cut"))
    assert(ConfigLoader.parse(ConfigWriter.toJson(c1)).isRight)
  }

  test("enemy shapes survive saving and loading") {
    val c0 = cfg
    val c1 = ConfigEdits.updateEnemy(c0, "Crate")(_.copy(shape = EnemyShape.Hexagon))
    val back = ConfigLoader.parse(ConfigWriter.toJson(c1)).toOption.get
    assertEquals(back.enemies.find(_.name == "Crate").map(_.shape), Some(EnemyShape.Hexagon))
    assertEquals(back.enemies.find(_.name == "Cardboard").map(_.shape), Some(EnemyShape.Square))
  }

class TowerEditsTest extends munit.FunSuite:
  private val cfg = ConfigLoader.bundled().fold(e => throw RuntimeException(e), identity)

  test("renaming a tower keeps the loadout and twists pointing at it, and the config still loads") {
    val target = cfg.twists.head.targetTower
    val next   = ConfigEdits.renameTower(cfg, target, "Renamed Box")
    assert(next.twists.head.targetTower == "Renamed Box")
    assert((next.loadout.unlocked ++ next.loadout.locked).contains("Renamed Box"))
    assert(ConfigLoader.parse(ConfigWriter.toJson(next)).isRight)
  }

  test("moving a tower between starting, free and locked always gives a loadable config") {
    val name = cfg.towers.last.name
    import ConfigEdits.TowerAccess.*
    Vector(Locked, Unlocked, Starting, Locked).foldLeft(cfg) { (c, a) =>
      val next = ConfigEdits.setTowerAccess(c, name, a)
      assertEquals(ConfigEdits.towerAccess(next, name), a)
      assert(ConfigLoader.parse(ConfigWriter.toJson(next)).isRight, a)
      next
    }
  }
