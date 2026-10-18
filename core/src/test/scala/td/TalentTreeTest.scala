package td

class TalentTreeTest extends TestBase:
  private def talent(id: String, layer: TalentLayer, needs: String*) =
    Talent(id, id, layer, 1, needs.toVector, Effect("reroll", Map.empty))

  test("columns are the layers; a talent sits level with what it needs, below what is already placed") {
    val ts = Vector(
      talent("a", TalentLayer.Filler), talent("b", TalentLayer.Filler), talent("c", TalentLayer.Filler),
      talent("x", TalentLayer.Core, "c"), talent("y", TalentLayer.Core, "a"), talent("z", TalentLayer.Core, "a"),
      talent("deep", TalentLayer.Deep, "x"))
    val at = TalentTree.layout(ts).map(n => n.talent.id -> (n.column, n.row)).toMap
    assertEquals(at("a"), (0, 0))
    assertEquals(at("c"), (0, 2))
    assertEquals(at("y"), (1, 0))
    assertEquals(at("z"), (1, 1))
    assertEquals(at("x"), (1, 2))
    assertEquals(at("deep"), (2, 2))
    assertEquals(TalentTree.rows(ts), 3)
    assertEquals(TalentTree.edges(ts).toSet,
      Set(TalentTree.Edge("c", "x"), TalentTree.Edge("a", "y"), TalentTree.Edge("a", "z"), TalentTree.Edge("x", "deep")))
  }

  test("the shipped tree fits the talent screen and every talent gets its own spot") {
    val nodes = TalentTree.layout(Config.talents)
    assertEquals(nodes.size, Config.talents.size)
    assertEquals(nodes.map(n => (n.column, n.row)).distinct.size, nodes.size)
    assert(TalentTree.rows(Config.talents) <= 9)
  }
