package td

class ConfigCodecTest extends munit.FunSuite:
  test("composition format/parse round-trip") {
    val c = Vector("Cardboard" -> 6, "Paper Cut" -> 2)
    assertEquals(ConfigCodec.parseComposition(ConfigCodec.formatComposition(c)), Right(c))
  }

  test("composition rejects non-integer counts and malformed entries") {
    assert(ConfigCodec.parseComposition("Grunt:x").isLeft)
    assert(ConfigCodec.parseComposition("Grunt:-1").isLeft)
    assert(ConfigCodec.parseComposition("nonsense").isLeft)
  }

  test("waypoints format/parse round-trip and reject bad tokens") {
    val w = Vector(GridPos(0, 17), GridPos(17, 17))
    assertEquals(ConfigCodec.parseWaypoints(ConfigCodec.formatWaypoints(w)), Right(w))
    assert(ConfigCodec.parseWaypoints("1,2 3").isLeft)
  }

  test("names and branches round-trip") {
    assertEquals(ConfigCodec.parseNames("Cardboard, Paper Cut ,Ember"), Vector("Cardboard", "Paper Cut", "Ember"))
    val b = Vector(Branch(RouteSide.Right, 2, 5, Vector(GridPos(8, 3))))
    assertEquals(ConfigCodec.parseBranches(ConfigCodec.formatBranches(b)), Right(b))
  }