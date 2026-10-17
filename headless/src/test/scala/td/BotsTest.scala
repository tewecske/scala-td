package td

import munit.{BeforeEach, FunSuite}
import java.nio.file.{Files, Paths}
import scala.util.Random

class BotsTest extends FunSuite:

  private val biome = Scenario.DefaultBiome

  override def beforeEach(context: BeforeEach): Unit =
    Config.reload()
    Config.clearActiveBiome()
    Grid.reset()
    super.beforeEach(context)

  private def spender(seed: Long): Player = Players.create("spender", Knowledge(), seed).toOption.get

  test("spender plays the same game for the same seed, a different one for another seed") {
    val a = Scenario.play(spender(1L), biome, 0, 1L, 5)
    val b = Scenario.play(spender(1L), biome, 0, 1L, 5)
    val c = Scenario.play(spender(2L), biome, 0, 2L, 5)
    assertEquals(a.copy(wallMs = 0L), b.copy(wallMs = 0L))
    assertNotEquals(a.commands, c.commands)
    assert(!a.outcome.timedOut)
  }

  test("explorer refusals are exactly the cells the level does not allow") {
    val explorer = new Explorer(Knowledge(), new Random(7), 1.0)
    Scenario.play(explorer, biome, 0, 7L, 5)
    val trace = explorer.trace
    assert(trace.refused.nonEmpty)
    assert(trace.refused.forall(c => !Grid.isBuildable(c, 0)))
    assert(trace.accepted.nonEmpty)
    assert(trace.accepted.forall { case (_, c) => Grid.isBuildable(c, 0) })
  }

  test("training is deterministic for a fixed seed") {
    val a = Training.train(Knowledge(), 2, 6, 11L, 5, biome, 0, Vector.empty, (_, _, _) => (), _ => ())
    val b = Training.train(Knowledge(), 2, 6, 11L, 5, biome, 0, Vector.empty, (_, _, _) => (), _ => ())
    assertEquals(a, b)
    assertEquals(a.batches, 2)
  }

  test("learning remembers refused cells") {
    val k = Training.train(Knowledge(), 1, 6, 5L, 5, biome, 0, Vector.empty, (_, _, _) => (), _ => ())
    assert(k.rejected.nonEmpty)
    assert(k.rejected.forall(c => !Grid.isBuildable(c, 0)))
  }

  test("knowledge survives a JSON round trip") {
    val k    = Training.train(Knowledge(), 2, 6, 3L, 5, biome, 0, Vector.empty, (_, _, _) => (), _ => ())
    val back = KnowledgeStore.parse(KnowledgeStore.toJson(k)).toOption.get
    def close(a: Double, b: Double): Boolean = math.abs(a - b) < 1e-9
    assertEquals(back.batches, k.batches)
    assertEquals(back.rejected, k.rejected)
    assertEquals(back.towerWeights.keySet, k.towerWeights.keySet)
    assert(k.towerWeights.forall((n, w) => close(back.towerWeights(n), w)))
    assertEquals(back.cellWeights.keySet, k.cellWeights.keySet)
    assert(k.cellWeights.forall((c, w) => close(back.cellWeights(c), w)))
    assert(close(back.waitRate, k.waitRate))
  }

  private def waveOf(cfg: GameConfig, wave: Int) = cfg.levels.head.waves(wave)
  private def specOf(cfg: GameConfig, n: String) = cfg.enemies.find(_.name == n).get
  private def bosses(cfg: GameConfig) =
    cfg.levels.head.waves.map(_.composition.filter { case (n, _) => specOf(cfg, n).boss })

  test("hardening a level only reshapes its waves: more units, later waves move up the ladder, stats untouched") {
    val base = Config.game
    val (hard, _) = Balance.harden(base, Balance.Steps(0.25, 0.1), biome, 0)
    assert(ConfigLoader.parse(ConfigWriter.toJson(hard)).isRight)
    def rungUp(wave: Int): Boolean =
      val before = waveOf(base, wave)
      waveOf(hard, wave).composition.exists { case (n, c) =>
        base.enemies.exists(e => e.promotesTo.contains(n)) && c > before.composition.find(_._1 == n).fold(0)(_._2) }
    assertEquals(waveOf(hard, 0), waveOf(base, 0), "the first wave is never hardened")
    hard.levels.head.waves.zip(base.levels.head.waves).drop(1).foreach { (a, b) =>
      assert(a.count > b.count)
      assertEquals(a.composition.map(_._2).sum, a.count)
    }
    assert(!rungUp(0), "the first wave never gets promoted units")
    assert(rungUp(1))
    assertEquals(hard.enemies, base.enemies)
    assertEquals(hard.towers, base.towers)
    assertEquals(bosses(hard), bosses(base))
  }

  test("easing a level removes units and moves one down its ladder; a wave never empties") {
    val base = Config.game
    val (easy, _) = Balance.ease(base, Balance.Steps(0.25, 0.1), biome, 0)
    val last = base.levels.head.waves.size - 1
    assert(waveOf(easy, last).count < waveOf(base, last).count)
    val (tiny, _) = (0 until 40).foldLeft((base, "")) { case ((c, _), _) => Balance.ease(c, Balance.Steps(0.25, 0.5), biome, 0) }
    assert(tiny.levels.head.waves.forall(_.count >= 1))
    assertEquals(bosses(tiny), bosses(base))
    assertEquals(tiny.enemies, base.enemies)
  }

  test("a global step scales every enemy's health and speed, and easing undoes it") {
    val base = Config.game
    val g    = Balance.GlobalSteps(1.1, 1.03)
    val hard = Balance.scaleEnemies(base, g, harder = true)
    base.enemies.zip(hard.enemies).foreach { (a, b) =>
      assertEqualsFloat(b.baseHp, a.baseHp * 1.1f, 1e-3f)
      assertEqualsFloat(b.baseSpeed, a.baseSpeed * 1.03f, 1e-4f)
    }
    val back = Balance.scaleEnemies(hard, g, harder = false)
    base.enemies.zip(back.enemies).foreach((a, b) => assertEqualsFloat(b.baseHp, a.baseHp, 1e-3f))
    assertEquals(hard.levels, base.levels)
  }

  test("the verdict separates too easy, too hard and in band") {
    val s = Balance.Settings(1, 1, 1, 1, easyWin = 0.9, easyLives = 2.0, hardWin = 0.5,
      Balance.Steps(0.25, 0.1), 1L, 5, biome, Vector.empty)
    val easy  = Stats(games = 10, wins = 10, livesLostWon = 0.0, goldLeftWon = 0.0, goldSpent = 0.0, towers = 0.0, steps = 0.0)
    assertEquals(Balance.verdict(easy, s), Balance.Verdict.TooEasy)
    assertEquals(Balance.verdict(easy.copy(wins = 7, livesLostWon = 5.0), s), Balance.Verdict.Ok)
    assertEquals(Balance.verdict(easy.copy(wins = 2), s), Balance.Verdict.TooHard)
  }

  test("spender spends all its gold on the cheapest tower in one go") {
    val w0    = Replay.scenarioWorld(1L, biome, 0)
    val cheap = Config.game.towers.minBy(_.cost)
    val cmds  = Config.withContext(biome, 0)(spender(1L).decide(w0))
    assertEquals(cmds.size, w0.gold / cheap.cost)
    assert(cmds.forall { case Command.Build(_, n) => n == cheap.name; case _ => false })
  }

  test("knowledge learned on another map is discarded") {
    val k = Knowledge(batches = 3, route = "other")
    val path = KnowledgeStore.pathFor("bots-test", 0)
    KnowledgeStore.save(path, k)
    try
      val got = Config.withContext(biome, 0)(KnowledgeStore.forLevel("bots-test", 0, _ => ()))
      assertEquals(got.batches, 0)
    finally Files.deleteIfExists(Paths.get(path))
  }

  test("a recorded game replays to exactly the same end state through the file format") {
    val dir = Files.createTempDirectory("replays").toString
    val game = Scenario.play(spender(1L), biome, 0, 1L, 5)
    val hash = ReplayStore.saveConfig(dir, Config.game)
    val replay = Replay(
      kind = "scenario", bot = "spender", seed = game.seed, biome = biome, level = 0, think = 5,
      config = hash, result = game.result, steps = game.outcome.steps, commands = game.commands
    )
    val path = ReplayStore.write(dir, "g", replay)
    val loaded = Replay.parse(Files.readString(Paths.get(path))).toOption.get
    assertEquals(loaded, replay)
    assertEquals(Replay.verify(loaded), game.result)
    assert(game.commands.nonEmpty)
  }

  test("a replay on a later level restores the same world and config") {
    val dir = Files.createTempDirectory("replays").toString
    val level = Config.levelCount - 1
    val game = Scenario.play(spender(2L), biome, level, 2L, 5)
    val hash = ReplayStore.saveConfig(dir, Config.game)
    val cfg = ReplayStore.loadConfig(dir, hash).toOption.get
    assertEquals(cfg.levels.size, Config.levelCount)
    val replay = Replay(
      kind = "scenario", bot = "spender", seed = game.seed, biome = biome, level = level, think = 5,
      config = hash, result = game.result, steps = game.outcome.steps, commands = game.commands
    )
    assertEquals(Replay.verify(replay), game.result)
  }

  test("stage options: level 1 is the plain run; level 2 crosses each unlock with every draft pick") {
    assertEquals(Stages.options(0, biome).map(_.unlock), Vector(None))
    val money = Stages.budget(1, biome)
    assertEquals(money, Config.PayoutPerLevel + Config.PayoutPerWave * Config.levelAt(0).waves.size)
    val opts  = Stages.options(1, biome)
    val plain = opts.filter(_.unlock.isEmpty)
    assertEquals(plain.size, DraftGen.eligibleItems(Vector.empty).size)
    assert(plain.forall(o => o.setup.relics.size + o.setup.augment.size == 1))
    // every talent chain offered is affordable and complete
    opts.flatMap(_.setup.talents.toVector).distinct.foreach(t => assert(Config.talents.exists(_.id == t)))
    opts.filter(_.setup.talents.nonEmpty).foreach { o =>
      val cost = o.setup.talents.map(id => Config.talents.find(_.id == id).get.cost).sum
      assert(cost <= money)
      assert(Meta.purchase(MetaData(cost, Vector.empty), o.setup.talents.head).isRight)
    }
  }

  test("a game with talents, a relic and a pending augment replays exactly through the file format") {
    val dir   = Files.createTempDirectory("replays").toString
    val setup = Setup(loadout = Vector("Matchbox"), talents = Vector("filler-damage"), augment = Some("cornerstone"),
      livesLost = 2)
    val game  = Scenario.play(spender(4L), biome, 1, 4L, 5, setup = setup)
    assertEquals(game.outcome.livesStart, Config.StartLives - 2)
    assert(game.commands.flatMap(_._2).exists { case Command.AttachAugment(_) => true; case _ => false })
    val hash  = ReplayStore.saveConfig(dir, Config.game)
    val replay = Replay(
      kind = "scenario", bot = "spender", seed = game.seed, biome = biome, level = 1, think = 5,
      config = hash, result = game.result, steps = game.outcome.steps, commands = game.commands, setup = setup
    )
    val loaded = Replay.parse(Files.readString(Paths.get(ReplayStore.write(dir, "s", replay)))).toOption.get
    assertEquals(loaded, replay)
    assertEquals(Replay.verify(loaded), game.result)
  }

  test("a cleared single level is recorded as CLEARED, and old RUNNING recordings read as CLEARED") {
    val game = Scenario.play(spender(1L), biome, 0, 1L, 5)
    assert(game.outcome.won)
    assert(game.result.startsWith("CLEARED "), game.result)
    val replay = Replay(
      kind = "scenario", bot = "spender", seed = 1L, biome = biome, level = 0, think = 5,
      config = "x", result = "RUNNING" + game.result.drop("CLEARED".length), steps = game.outcome.steps,
      commands = game.commands)
    val loaded = Replay.parse(Replay.toJson(replay)).toOption.get
    assertEquals(loaded.result, game.result)
    assertEquals(loaded.outcome, "CLEARED")
    assertEquals(loaded.stat("kills"), Some(game.outcome.kills.toString))
    assertEquals(Replay.verify(loaded), game.result)
  }

  test("seeking a playback forward or back lands on the same state as playing straight through") {
    val game = Scenario.play(spender(2L), biome, 0, 2L, 5)
    val r = Replay(kind = "scenario", bot = "spender", seed = 2L, biome = biome, level = 0, think = 5,
      config = "x", result = game.result, steps = game.outcome.steps, commands = game.commands)
    val start  = Playback.start(r)
    val middle = start.seek(r.steps / 2)
    assertEquals(middle.index, r.steps / 2)
    val back = start.seek(r.steps - 10).seek(r.steps / 2)
    assertEquals(back.world, middle.world)
    assertEquals(Replay.resultOf(start.seek(r.steps).world, r.steps, Some(0)), game.result)
    assertEquals(start.copy(speed = 64.0).faster(1).speed, 64.0)
    assertEquals(start.copy(speed = 0.25).faster(-1).speed, 0.25)
    assertEquals(start.faster(1).speed, 2.0)
  }

  test("a rolled game plays the run the game rolls from its seed and replays exactly") {
    val dir   = Files.createTempDirectory("replays").toString
    val setup = Setup(loadout = Vector("Cuboid"), rolled = true)
    val seed  = (1L to 50L).find(s => RunGen.roll(s).biome != biome).get
    val game  = Scenario.play(spender(seed), biome, 0, seed, 5, setup = setup)
    val start = Replay.scenarioWorld(seed, biome, 0, setup)
    assertEquals(start.run.biome, RunGen.roll(seed).biome)
    assertEquals(start.run.twists, RunGen.roll(seed).twists)
    val replay = Replay(
      kind = "scenario", bot = "spender", seed = seed, biome = start.run.biome, level = 0, think = 5,
      config = ReplayStore.saveConfig(dir, Config.game), result = game.result, steps = game.outcome.steps,
      commands = game.commands, setup = setup
    )
    val loaded = Replay.parse(Files.readString(Paths.get(ReplayStore.write(dir, "r", replay)))).toOption.get
    assertEquals(loaded, replay)
    assertEquals(Replay.verify(loaded), game.result)
  }

  test("level 1 is judged by lives lost: a clean sweep is too easy, a costly win is fine, a loss is too hard") {
    val s = Stages.Settings(1, 1, 1, firstLow = 0.6, livesLow = 1.0, livesHigh = 3.0, goodShare = 0.3, winBar = 0.5,
      Balance.Steps(0.25, 0.1), 1L, 5, biome)
    def at(winRate: Double, lost: Double) =
      Vector(Stages.Result(Stages.Entry(None, Setup()),
        Stats(games = 10, wins = (winRate * 10).toInt, livesLostWon = lost, goldLeftWon = 0, goldSpent = 0, towers = 0, steps = 0)))
    assertEquals(Stages.verdict(0, at(1.0, 0.0), s), Balance.Verdict.TooEasy)
    assertEquals(Stages.verdict(0, at(0.9, 2.0), s), Balance.Verdict.Ok)
    assertEquals(Stages.verdict(0, at(0.9, 3.5), s), Balance.Verdict.TooHard)
    assertEquals(Stages.verdict(0, at(0.4, 2.0), s), Balance.Verdict.TooHard)
  }

  test("generated levels have edge-to-edge routes that never touch themselves") {
    val routes = (0 until 40).map(i => LevelGen.randomRoute(new Random(i.toLong), 20))
    routes.foreach { wps =>
      val last = 19
      assert(wps.size >= 3)
      assert(wps.forall(p => p.x >= 0 && p.x <= last && p.y >= 0 && p.y <= last))
      val onEdge = (p: GridPos) => p.x == 0 || p.y == 0 || p.x == last || p.y == last
      assert(onEdge(wps.head) && onEdge(wps.last))
      wps.sliding(2).foreach { case Seq(a, b) =>
        assert(a.x == b.x || a.y == b.y, s"segment $a -> $b is not axis-aligned")
        assert(a != b)
      }
    }
  }

  test("generated levels splice into the config and still load") {
    val text = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/config.json"), "UTF-8").mkString
    val before = ConfigLoader.parse(text).toOption.get.levels.size
    val fragment = LevelGen.toJson(LevelGen.generate(new Random(9L), 2, 20, LevelGen.Names, before))
    val spliced = LevelGen.splice(text, "levels", fragment)
    assert(spliced.isRight, spliced.toString)
    assertEquals(ConfigLoader.parse(spliced.toOption.get).toOption.get.levels.size, before + 2)
  }

  /** The cells of one axis-aligned segment, in the order a walker meets them. */
  private def walk(a: GridPos, b: GridPos): Vector[GridPos] =
    val dx = Integer.signum(b.x - a.x)
    val dy = Integer.signum(b.y - a.y)
    val n  = math.max(math.abs(b.x - a.x), math.abs(b.y - a.y))
    (0 to n).toVector.map(k => GridPos(a.x + dx * k, a.y + dy * k))

  test("looped roads circle a block without touching themselves") {
    List(10, 20).foreach { size =>
      (0 until 30).foreach { i =>
        val cells = LevelGen.randomLoop(new Random(i.toLong), size)
        val path  = cells.sliding(2).flatMap { case Seq(a, b) => walk(a, b) }.toVector.distinct
        val last  = size - 1
        assert(path.forall(p => p.x >= 0 && p.x <= last && p.y >= 0 && p.y <= last))
        assert(cells.head.x == 0 && cells.last.x == 0 || cells.head.y == 0 && cells.last.y == 0 ||
          cells.head.x == last && cells.last.x == last || cells.head.y == last && cells.last.y == last)
        val touching = for
          (a, ia) <- path.zipWithIndex
          (b, ib) <- path.zipWithIndex
          if ib > ia + 1 && math.abs(a.x - b.x) + math.abs(a.y - b.y) == 1
        yield (a, b)
        assert(touching.isEmpty, s"size $size seed $i: road touches itself at ${touching.headOption}")
      }
    }
  }

  test("forked roads have a valid lane that rejoins the road, and the level still loads") {
    (0 until 10).foreach { i =>
      val (route, branches) = LevelGen.randomFork(new Random(i.toLong), 10)
      assertEquals(branches.size, 1)
      val b = branches.head
      assert(b.from + 2 <= b.to && b.to < route.size)
      assertEquals(b.waypoints.size, 1)
    }
    val text = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/config.json"), "UTF-8").mkString
    val cfg  = ConfigLoader.parse(text).toOption.get
    cfg.biomes.filter(_.levels.nonEmpty).foreach { biome =>
      biome.levels.foreach { lv =>
        assert(lv.route.waypoints.forall(p => p.x >= 0 && p.x < biome.gridSize.getOrElse(cfg.board.gridSize)))
      }
    }
  }
