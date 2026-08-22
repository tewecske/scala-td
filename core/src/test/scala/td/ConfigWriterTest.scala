package td

class ConfigWriterTest extends munit.FunSuite:
  private val cfg = Config.game

  test("bundled config round-trips through toJson -> parse") {
    assertEquals(ConfigLoader.parse(ConfigWriter.toJson(cfg)), Right(cfg))
  }

  test("cell-scaled range/splash/aura radius are divided back to cell units") {
    val json = ConfigWriter.toJson(cfg)
    assert(json.contains("\"range\": 3.2"))          // tower Cuboid range (cells)
    assert(json.contains("\"splashRadius\": 1.2"))   // Hole Punch (cells)
  }

  test("colorHex round-trips alpha and opaque colors") {
    assertEquals(ConfigWriter.colorHex(ConfigLoader.parseColor("#5C63A5")), "#5C63A5")
    assertEquals(ConfigWriter.colorHex(ConfigLoader.parseColor("#64C15F99")), "#64C15F99")
  }

  test("write produces a file the loader reads back") {
    val f = java.io.File.createTempFile("cfg", ".json")
    f.deleteOnExit()
    ConfigWriter.write(cfg, f.getAbsolutePath)
    assertEquals(ConfigLoader.load(f.getAbsolutePath), Right(cfg))
  }