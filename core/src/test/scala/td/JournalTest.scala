package td

import java.nio.file.Files

class JournalTest extends munit.FunSuite:
  private def tmpPath(suffix: String): String =
    Files.createTempFile(s"journal-$suffix", ".json").toString

  test("missing file loads as empty journal") {
    val path = tmpPath("missing")
    new java.io.File(path).delete()
    assertEquals(Journal.load(path), Right(JournalData.empty))
  }

  test("save/load round-trips entries, biomes, twists") {
    val path = tmpPath("roundtrip")
    val data = JournalData(
      entries = Vector(JournalEntry("Brick", "resist.Kinetic", Precision.Studied, Some("0.35")),
                       JournalEntry("Ember", "resist.Fire", Precision.Sighting, Some("resists"))),
      biomes = Vector("cube-farm"),
      twists = Vector("brittle")
    )
    Journal.save(path, data)
    assertEquals(Journal.load(path), Right(data))
  }

  test("applyEvent upgrades Sighting to Studied and never downgrades") {
    val d1 = JournalData.empty.applyEvent(DiscoveryEvent("Brick", "resist.Kinetic", Precision.Sighting, "resists"))
    assertEquals(d1.entries.head.tier, Precision.Sighting)
    val d2 = d1.applyEvent(DiscoveryEvent("Brick", "resist.Kinetic", Precision.Studied, "0.35"))
    assertEquals(d2.entries.head.tier, Precision.Studied)
    assertEquals(d2.entries.head.value, Some("0.35"))
    val d3 = d2.applyEvent(DiscoveryEvent("Brick", "resist.Kinetic", Precision.Sighting, "resists"))
    assertEquals(d3.entries.head.tier, Precision.Studied)
  }

  test("applyRun dedups biome and twist ids") {
    val run = RunContext(seed = 1L, biome = "cube-farm", twists = Vector("brittle"))
    val d   = JournalData.empty.applyRun(run).applyRun(run)
    assertEquals(d.biomes, Vector("cube-farm"))
    assertEquals(d.twists, Vector("brittle"))
  }

  test("first hit emits Sighting; Nth hit emits Studied with the exact value") {
    val collected = fire("Brick", DamageKind.Kinetic, Config.StudyHits)
    assert(collected.exists(e => e.tier == Precision.Sighting && e.value == "resists"))
    assert(collected.exists(e => e.tier == Precision.Studied && e.value == "0.35"))
  }

  test("spawning an enemy with an aura records its ability as Studied") {
    val run = RunContext(seed = 1L, biome = "tutorial",
      resistTheme = Config.biome("tutorial").resistTheme, roster = Vector("Commander"))
    val w = Sim.initial(run).copy(spawnQueue = Vector("Commander"), spawnTimer = 0f, breakTimer = 0f)
    val stepped = Sim.step(w, Config.MaxStepDt, Vector.empty)
    assert(stepped.enemies.exists(_.tpe == "Commander"))
    assert(stepped.events.exists(e => e.enemy == "Commander" && e.traitKey == "aura" && e.tier == Precision.Studied))
  }

  private def fire(enemyTpe: String, kind: DamageKind, n: Int): Vector[DiscoveryEvent] =
    val b    = Config.biome("tutorial")
    val run  = RunContext(seed = 1L, biome = b.id, resistTheme = b.resistTheme, roster = b.roster)
    val e = Enemy(id = 1L, progress = 0f, hp = 1e6f, maxHp = 1e6f, speed = 42f,
      tpe = enemyTpe, level = 0, resist = Config.enemySpec(enemyTpe).resist)
    var w = Sim.initial(run).copy(spawnQueue = Vector.empty, breakTimer = 999f)
    var out = Vector.empty[DiscoveryEvent]
    for _ <- 0 until n do
      val (x, y) = e.pos
      val p = Projectile(x, y, 1L, kind, 1f, 1e6f, 0f, None)
      w = Sim.step(w.copy(enemies = Vector(e), shots = Vector(p)), Config.MaxStepDt, Vector.empty)
      out = out ++ w.events
      w = w.copy(events = Vector.empty)
    out
