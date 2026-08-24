package td

class ConfigTest extends TestBase:
  private val cfg = Config.game

  test("new collections load from bundled config") {
    assert(cfg.biomes.nonEmpty)
    assert(cfg.twists.nonEmpty)
    assert(cfg.relics.nonEmpty)
    assert(cfg.augments.nonEmpty)
    assert(cfg.talents.nonEmpty)
  }

  test("meta payout economy loads from config") {
    assertEquals(cfg.meta.payoutPerLevel, 10)
    assertEquals(cfg.meta.payoutPerWave, 2)
    assertEquals(cfg.meta.payoutWinBonus, 25)
  }

  test("draft tuning loads from config") {
    assertEquals(cfg.draft.offerCount, 3)
    assertEquals(cfg.draft.rerollCost, 25)
    assertEquals(cfg.draft.poolRounds, 10)
  }

  test("tutorial biome exists and real biomes have distinct resist themes") {
    assert(cfg.biomes.exists(_.id == "tutorial"))
    assertEquals(cfg.biomes.size, 5)
    cfg.biomes.foreach(b => assertNotEquals(b.resistTheme.primary, b.resistTheme.secondary))
  }

  test("four twists each target an existing tower") {
    assertEquals(cfg.twists.size, 4)
    val towerNames = cfg.towers.map(_.name).toSet
    cfg.twists.foreach(t => assert(towerNames.contains(t.targetTower)))
  }

  test("loadout config: max 3, Cuboid default, Hole Punch locked, rest unlocked") {
    assertEquals(cfg.loadout.maxSelected, 3)
    assertEquals(cfg.loadout.defaultSelected, Vector("Cuboid"))
    assertEquals(cfg.loadout.locked, Vector("Hole Punch"))
    val unlocked = cfg.loadout.unlocked.toSet
    assert(unlocked.contains("Cuboid"))
    assert(unlocked.contains("Matchbox"))
    assert(unlocked.contains("Ice Cube"))
    assert(unlocked.contains("Jack-in-the-Box"))
    // every tower is either unlocked or locked, with no gaps or overlaps
    assertEquals((unlocked ++ cfg.loadout.locked.toSet), cfg.towers.map(_.name).toSet)
  }

  test("biome rosters reference existing enemies") {
    val enemyNames = cfg.enemies.map(_.name).toSet
    cfg.biomes.foreach(b => b.roster.foreach(e => assert(enemyNames.contains(e))))
  }

  test("talent tree has 3 cores and prereqs resolve") {
    assertEquals(cfg.talents.count(_.layer == TalentLayer.Core), 3)
    cfg.talents.foreach(t => t.prereqs.foreach(p => assert(cfg.talents.exists(_.id == p))))
  }

  test("gated relics reference an existing talent") {
    cfg.relics.foreach { r =>
      r.requiredTalent.foreach(t => assert(cfg.talents.exists(_.id == t)))
      if r.rarity != Rarity.Common then assert(r.requiredTalent.isDefined)
    }
  }

  test("duplicate biome id is rejected") {
    val json = minimalConfig(
      biomes = Some(s"[ ${biomeJson("tutorial", "Cardboard")}, ${biomeJson("tutorial", "Cardboard")} ]")
    )
    assert(ConfigLoader.parse(json).left.exists(_.contains("duplicate biome")))
  }

  test("unknown rarity and unknown roster enemy are rejected") {
    assert(ConfigLoader.parse(minimalConfig(relicRarity = "Mythic")).left.exists(_.contains("unknown rarity")))
    assert(ConfigLoader.parse(minimalConfig(roster = "Brick")).left.exists(_.contains("unknown enemy")))
  }

  private def biomeJson(id: String, roster: String): String =
    s"""{ "id": "$id", "name": "$id", "roster": ["$roster"], "environment": null,
       "resistTheme": { "primary": "Kinetic", "primaryAmount": 0.0, "secondary": "Fire", "secondaryAmount": 0.0 } }"""

  private def minimalConfig(
      biomes: Option[String] = None,
      relicRarity: String = "Common",
      roster: String = "Cardboard"
  ): String =
    val biomeSection = biomes.getOrElse(s"[ ${biomeJson("tutorial", roster)} ]")
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
    { "name": "L1", "route": { "waypoints": [[0, 0], [1, 0]] },
      "waves": [ { "count": 1, "spawnInterval": 1.0, "hpScale": 1.0, "composition": { "Cardboard": 1 } } ] }
  ],
  "colors": {
    "background": "#021A01", "path": "#328A2E", "build": "#12520F", "gridLine": "#00000040", "enemy": "#ED7477",
    "shot": "#FFE6A5", "shotFire": "#ED7477", "shotFrost": "#9CA2E1", "shotSplash": "#5C63A5", "shotBoom": "#FEA4A6",
    "burnTint": "#ED7477", "slowTint": "#9CA2E1", "buffTint": "#9DEC99", "panel": "#030516", "hoverOk": "#64C15F99",
    "hoverBad": "#ED747780", "range": "#9CA2E166", "buttonFill": "#323875", "hpBarBack": "#030516", "hpBarFill": "#9DEC99",
    "text": "#FFE6A5", "dimText": "#5C63A5"
  },
  "biomes": $biomeSection,
  "twists": [],
  "relics": [
    { "id": "r1", "name": "R1", "rarity": "$relicRarity", "requiredTalent": null,
      "effect": { "kind": "squareDeal", "params": {} } }
  ],
  "augments": [],
  "talents": []
}"""
