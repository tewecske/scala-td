package td

import java.nio.file.Files

class DisplaySettingsTest extends munit.FunSuite:

  test("settings survive a save and load; a missing or broken file gives the defaults") {
    val dir  = Files.createTempDirectory("settings")
    val path = dir.resolve("settings.json").toString
    assertEquals(DisplaySettings.load(path), DisplaySettings())
    val s = DisplaySettings(WindowMode.Borderless, 2560, 1440)
    DisplaySettings.save(s, path)
    assertEquals(DisplaySettings.load(path), s)
    Files.writeString(dir.resolve("settings.json"), "{ not json")
    assertEquals(DisplaySettings.load(path), DisplaySettings())
  }

  test("window sizes stop at the monitor and always include it") {
    val sizes = DisplaySettings.windowSizes(1920, 1200)
    assert(sizes.forall((w, h) => w <= 1920 && h <= 1200), sizes)
    assert(sizes.contains((1920, 1200)))
    assert(sizes.contains((1280, 720)))
    assertEquals(sizes, sizes.sortBy(_._1))
  }

  test("cycling wraps around both ways and snaps an unknown size to the closest option") {
    val opts = Vector((1280, 720), (1600, 900), (1920, 1080))
    assertEquals(DisplaySettings.cycle(opts, (1600, 900), 1), (1920, 1080))
    assertEquals(DisplaySettings.cycle(opts, (1920, 1080), 1), (1280, 720))
    assertEquals(DisplaySettings.cycle(opts, (1280, 720), -1), (1920, 1080))
    assertEquals(DisplaySettings.cycle(opts, (1700, 950), 0), (1600, 900))
    assertEquals(DisplaySettings.cycle(Vector.empty, (800, 600), 1), (800, 600))
  }
