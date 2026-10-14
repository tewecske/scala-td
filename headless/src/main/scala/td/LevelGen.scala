package td

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Random

/** Generates random levels as config JSON. A route runs from one board edge to
  * another, axis-aligned, and never touches itself (cells that are next to each
  * other but not on the same road are kept at least two apart). Shapes:
  *
  *  - snake: random turns, like a winding road.
  *  - loop:  mostly left turns, so the road circles back on itself.
  *  - fork:  a snake road plus a second lane that leaves it and rejoins it later.
  *
  * Three waves per level are drawn from the enemy roster, growing in size and
  * toughness. Sizes scale with the board, so a 10x10 map has short, quick routes.
  *
  * Output is plain JSON. It is spliced into a config file only if the result still
  * passes [[ConfigLoader]], so a bad roll can never corrupt the config.
  */
object LevelGen:

  val Names: Vector[String] = Vector(
    "The Stairwell", "Box Canyon", "Right Angle Ridge", "Cubicle Maze", "Loading Bay Four",
    "The Cul-de-Sac", "Corridor of Squares", "The Perimeter Loop", "Mezzanine", "Backroom Switchback"
  )

  val SmallNames: Vector[String] = Vector(
    "Shoebox Lane", "Tiny Crossing", "Cramped Loop", "Little Fork", "Closet Circuit", "Desk Drawer", "Paper Tray"
  )

  /** Shapes accepted by [[generate]]; `mixed` cycles through all three. */
  val Shapes: Vector[String] = Vector("snake", "loop", "fork")

  val SmallRoster: Vector[String] = Vector("Cardboard", "Paper Cut", "Cardboard II", "Ember", "Frostling", "Brick")

  final case class Generated(name: String, waypoints: Vector[GridPos], branches: Vector[Branch], waves: Vector[WaveSpec])

  /** Size-dependent limits, so a 10x10 board gets roads about a third the length of a 20x20 one. */
  private final case class Limits(segMin: Int, segMax: Int, maxSegments: Int, minCells: Int, maxCells: Int, baseWave: Int)

  private def limits(size: Int): Limits =
    Limits(
      segMin = if size >= 20 then 3 else 2,
      segMax = math.max(3, size / 2 - 1),
      maxSegments = size - 2,
      minCells = size * 9 / 4,
      maxCells = size * 7,
      baseWave = if size >= 20 then 8 else 4
    )

  /** Enemy types a generated wave may draw from, by how far into the campaign the
    * level sits: basic types first, then the armoured and aura types, then heavies.
    */
  private def pool(position: Int): Vector[String] =
    val early = Vector("Cardboard", "Paper Cut", "Cardboard II", "Ember", "Frostling")
    val mid   = Vector("Brick", "Commander", "Edgelord", "Paper Cut II", "Shieldbearer")
    val late  = Vector("Brick II", "Crate", "Crate II")
    early ++ (if position >= 1 then mid else Vector.empty) ++ (if position >= 3 then late else Vector.empty)

  /** Levels for `count` maps on a `size` board. `shapes` is cycled per level. */
  def generate(rng: Random, count: Int, size: Int, names: Vector[String], firstPosition: Int,
               shapes: Vector[String] = Vector("snake")): Vector[Generated] =
    (0 until count).toVector.map { i =>
      val shape = shapes(i % shapes.size)
      val (route, branches) = shape match
        case "fork" => randomFork(rng, size)
        case "loop" => (randomLoop(rng, size), Vector.empty)
        case _      => (randomRoute(rng, size), Vector.empty)
      val waves = (0 until 3).toVector.map(w => randomWave(rng, w, size, pool(firstPosition + i)))
      Generated(names(i % names.size), route, branches, waves)
    }

  /** A self-avoiding random road from an edge cell to another edge cell. */
  def randomRoute(rng: Random, size: Int): Vector[GridPos] =
    val l = limits(size)
    Iterator.continually(attempt(rng, size, l)).flatten.next()

  /** A road that circles a block and comes back to within one cell of where it
    * started, then leaves by the same edge. Built directly rather than by random
    * walk (a random walk almost never closes a ring on a small board). The gap of one
    * cell between the two ends is what keeps the road from touching itself. The whole
    * shape is mirrored and rotated at random.
    */
  def randomLoop(rng: Random, size: Int): Vector[GridPos] =
    val last = size - 1
    val lo   = math.max(3, size / 2 - 1)
    val hi   = size / 2 + 1
    val w    = lo + rng.nextInt(hi - lo + 1)
    val h    = lo + rng.nextInt(hi - lo + 1)
    val x0   = 2 + rng.nextInt(math.max(1, size - 2 - w - 1))
    val y0   = 1 + rng.nextInt(math.max(1, size - 3 - h))
    val corners = Vector(
      GridPos(0, y0), GridPos(x0, y0), GridPos(x0 + w, y0), GridPos(x0 + w, y0 + h),
      GridPos(x0, y0 + h), GridPos(x0, y0 + 2), GridPos(0, y0 + 2)
    )
    val swap   = rng.nextBoolean()
    val flipX  = rng.nextBoolean()
    val flipY  = rng.nextBoolean()
    def place(p: GridPos): GridPos =
      val (a, b) = if swap then (p.y, p.x) else (p.x, p.y)
      GridPos(if flipX then last - a else a, if flipY then last - b else b)
    corners.map(place)

  private def attempt(rng: Random, size: Int, l: Limits): Option[Vector[GridPos]] =
    val last  = size - 1
    val side  = rng.nextInt(4)
    val along = 1 + rng.nextInt(size - 2)
    val (start, inward) = side match
      case 0 => (GridPos(0, along), (1, 0))
      case 1 => (GridPos(last, along), (-1, 0))
      case 2 => (GridPos(along, 0), (0, 1))
      case _ => (GridPos(along, last), (0, -1))
    var cur      = start
    var visited  = Set(start)
    var cells    = 1
    var wps      = Vector(start)
    var heading  = inward
    var seg      = 0
    var failed   = false
    while !failed && seg < l.maxSegments && !(onEdge(cur, last) && seg >= 3 && cells >= l.minCells) do
      seg += 1
      val len  = l.segMin + rng.nextInt(l.segMax - l.segMin + 1)
      if seg > 1 then heading = turn(heading, rng)
      val path = (1 to len).map(k => GridPos(cur.x + heading._1 * k, cur.y + heading._2 * k))
      val ok   = path.forall { c =>
        c.x >= 0 && c.x <= last && c.y >= 0 && c.y <= last &&
          !visited.contains(c) && visited.forall(v => v == cur || manhattan(c, v) >= 2)
      }
      if !ok || cells + len > l.maxCells then failed = true
      else
        visited = visited ++ path
        cells += len
        cur = path.last
        wps = wps :+ cur
    if !failed && onEdge(cur, last) && seg >= 3 && cells >= l.minCells then Some(wps) else None

  /** Turns a heading 90 degrees, either way. */
  private def turn(h: (Int, Int), rng: Random): (Int, Int) =
    if rng.nextBoolean() then (-h._2, h._1) else (h._2, -h._1)

  /** A snake road with one extra lane. The lane leaves the road at waypoint `i`,
    * runs in an L-shape, and rejoins at waypoint `j` (j >= i + 2). The lane must not
    * touch the rest of the road, so enemies can take either side and both stay readable.
    */
  def randomFork(rng: Random, size: Int): (Vector[GridPos], Vector[Branch]) =
    Iterator.continually {
      val route = randomRoute(rng, size)
      randomBranch(rng, route, size).map(b => (route, Vector(b)))
    }.flatten.next()

  def randomBranch(rng: Random, wps: Vector[GridPos], size: Int): Option[Branch] =
    val trunk = wps.sliding(2).flatMap { case Seq(a, b) => cellsBetween(a, b) }.toSet
    val pairs = rng.shuffle((for i <- 0 until wps.size - 2; j <- i + 2 until wps.size yield (i, j)).toVector)
    pairs.iterator.flatMap { case (i, j) =>
      val a = wps(i)
      val b = wps(j)
      if a.x == b.x || a.y == b.y then None
      else
        val bypass = (i until j).flatMap(k => cellsBetween(wps(k), wps(k + 1))).toSet
        rng.shuffle(Vector(GridPos(a.x, b.y), GridPos(b.x, a.y))).iterator.flatMap { p =>
          val lane = (cellsBetween(a, p) ++ cellsBetween(p, b)).distinct.filterNot(c => c == a || c == b)
          val clear = lane.forall { c =>
            inBounds(c, size) && !trunk.contains(c) &&
              trunk.forall(t => bypass.contains(t) || manhattan(c, t) >= 2)
          }
          if clear then Some(Branch(sideOf(a, wps(i + 1), p), i, j, Vector(p))) else None
        }.nextOption()
    }.nextOption()

  private def sideOf(a: GridPos, next: GridPos, p: GridPos): RouteSide =
    val dx = next.x - a.x
    val dy = next.y - a.y
    val vx = p.x - a.x
    val vy = p.y - a.y
    if dx * vy - dy * vx > 0 then RouteSide.Left else RouteSide.Right

  def cellsBetween(a: GridPos, b: GridPos): Vector[GridPos] =
    if a.x == b.x then
      (math.min(a.y, b.y) to math.max(a.y, b.y)).toVector.map(GridPos(a.x, _))
    else
      (math.min(a.x, b.x) to math.max(a.x, b.x)).toVector.map(GridPos(_, a.y))

  private def inBounds(c: GridPos, size: Int): Boolean =
    c.x >= 0 && c.x < size && c.y >= 0 && c.y < size

  private def onEdge(c: GridPos, last: Int): Boolean =
    c.x == 0 || c.y == 0 || c.x == last || c.y == last

  private def manhattan(a: GridPos, b: GridPos): Int =
    math.abs(a.x - b.x) + math.abs(a.y - b.y)

  private def randomWave(rng: Random, index: Int, size: Int, types: Vector[String]): WaveSpec =
    val base   = limits(size).baseWave
    val kinds  = 2 + rng.nextInt(2)
    val chosen = rng.shuffle(types).take(kinds)
    val total  = base + rng.nextInt(base / 2 + 1) + index * (if size >= 20 then 2 else 1)
    val shares = chosen.map(_ => 1 + rng.nextInt(4))
    val sum    = shares.sum.toDouble
    val counts = chosen.indices.map(i => math.max(1, math.round(total * shares(i) / sum).toInt))
    val comp   = chosen.zip(counts).toVector
    WaveSpec(
      count = comp.map(_._2).sum,
      spawnInterval = round2(0.6f + rng.nextFloat() * 0.3f),
      hpScale = round2(1.0f + 0.2f * index + rng.nextFloat() * 0.15f),
      composition = comp
    )

  private def round2(x: Float): Float = math.round(x * 100f) / 100f

  /** The levels as the JSON fragment that goes inside a `levels` array. */
  def toJson(levels: Vector[Generated]): String =
    levels.map(levelJson).mkString(",\n")

  private def levelJson(g: Generated): String =
    val wps = g.waypoints.map(p => s"[${p.x},${p.y}]").mkString(",")
    val branches =
      if g.branches.isEmpty then ""
      else
        ", \"branches\": [" + g.branches.map { b =>
          val pts = b.waypoints.map(p => s"[${p.x},${p.y}]").mkString(",")
          s"""{ "side": "${b.side}", "from": ${b.from}, "to": ${b.to}, "waypoints": [$pts] }"""
        }.mkString(", ") + "]"
    val waves = g.waves.map { w =>
      val comp = w.composition.map { case (n, c) => s"\"$n\": $c" }.mkString(", ")
      s"""        { "count": ${w.count}, "spawnInterval": ${w.spawnInterval}, "hpScale": ${w.hpScale}, "composition": { $comp } }"""
    }.mkString(",\n")
    s"""    {
       |      "name": "${g.name}",
       |      "route": { "waypoints": [$wps]$branches },
       |      "waves": [
       |$waves
       |      ]
       |    }""".stripMargin

  /** A whole biome: its own board size and roster, holding the given levels. */
  def biomeJson(id: String, name: String, gridSize: Int, roster: Vector[String], levels: Vector[Generated]): String =
    val names = roster.map(n => s"\"$n\"").mkString(", ")
    s"""    {
       |      "id": "$id",
       |      "name": "$name",
       |      "roster": [$names],
       |      "environment": null,
       |      "resistTheme": { "primary": "Kinetic", "primaryAmount": 0.0, "secondary": "Fire", "secondaryAmount": 0.0 },
       |      "gridSize": $gridSize,
       |      "levels": [
       |${toJson(levels)}
       |      ]
       |    }""".stripMargin

  /** Splices `fragment` onto the end of the top-level array named `key` (for example
    * `"levels"` or `"biomes"`), keeping the rest of the file byte-for-byte. Returns the
    * new text only if it still parses.
    */
  def splice(configText: String, key: String, fragment: String): Either[String, String] =
    val matcher = java.util.regex.Pattern.compile("(?m)^(?: {2})?\"" + java.util.regex.Pattern.quote(key) + "\"").matcher(configText)
    val at = if matcher.find() then matcher.start() else -1
    if at < 0 then Left(s"no top-level \"$key\" array in config")
    else
      val open  = configText.indexOf('[', at)
      val close = matchingBracket(configText, open)
      if open < 0 || close < 0 then Left(s"\"$key\" array is not closed")
      else
        val body    = configText.substring(open + 1, close).stripTrailing
        val spliced = configText.substring(0, open + 1) + body + ",\n" + fragment + "\n  " + configText.substring(close)
        ConfigLoader.parse(spliced).map(_ => spliced)

  private def matchingBracket(text: String, open: Int): Int =
    var depth = 0
    var inString = false
    var i = open
    var found = -1
    while found < 0 && i < text.length do
      val c = text.charAt(i)
      if inString then
        if c == '"' then inString = false
      else if c == '"' then inString = true
      else if c == '[' then depth += 1
      else if c == ']' then
        depth -= 1
        if depth == 0 then found = i
      i += 1
    found

  /** Adds `count` levels on a `size` board. With `biome` = Some((id, name)) they form a
    * new biome of their own (the board size is per biome); without it they join the
    * global campaign, which must already be on a board of that size.
    */
  def writeInto(
      path: String,
      count: Int,
      size: Int,
      seed: Long,
      shapes: Vector[String],
      biome: Option[(String, String)]
  ): Either[String, Int] =
    val text = Files.readString(Paths.get(path), StandardCharsets.UTF_8)
    ConfigLoader.parse(text).flatMap { cfg =>
      biome match
        case Some((id, name)) =>
          if cfg.biomes.exists(_.id == id) then Left(s"biome '$id' already exists")
          else
            val levels = generate(new Random(seed), count, size, SmallNames, 1, shapes)
            val json   = biomeJson(id, name, size, SmallRoster, levels)
            splice(text, "biomes", json).map { spliced =>
              Files.writeString(Paths.get(path), spliced, StandardCharsets.UTF_8)
              levels.size
            }
        case None =>
          if size != cfg.board.gridSize then
            Left(s"global levels are on a ${cfg.board.gridSize}x${cfg.board.gridSize} board; use --biome for size $size")
          else
            val names  = new Random(seed).shuffle(Names)
            val levels = generate(new Random(seed), count, size, names, cfg.levels.size, shapes)
            splice(text, "levels", toJson(levels)).map { spliced =>
              Files.writeString(Paths.get(path), spliced, StandardCharsets.UTF_8)
              levels.size
            }
    }

/** The themed campaign: ten levels, five themes of two levels each. Every theme has a
  * variant ladder (base, veteran, captain), a basic unit it replaces, a boss per level,
  * and a map feature. The first five levels reuse the 10x10 routes of the shoebox biome;
  * the last five are new 14x14 routes.
  */
object Campaign:

  final case class Theme(name: String, basic: String, ladder: Vector[String], bosses: Vector[String], puddles: Boolean)

  val Themes: Vector[Theme] = Vector(
    Theme("wooden crates", "Cardboard", Vector("Crate", "Pallet", "Crate Captain"),
      Vector("The Trespasser", "The Trespasser II"), puddles = false),
    Theme("soggy cardboard", "Paper Cut", Vector("Drip", "Soaked Box", "Puddle Captain"),
      Vector("The Pentagon", "The Pentagon II"), puddles = true),
    Theme("frozen freezer", "Cardboard II", Vector("Frostbox", "Chilled Pallet", "Ice Captain"),
      Vector("Hexcellent", "Hexcellent II"), puddles = false),
    Theme("office paper", "Paper Cut II", Vector("Memo", "Stapled Stack", "Cubicle Captain"),
      Vector("The Roundabout", "The Roundabout II"), puddles = false),
    Theme("steel drums", "Brick II", Vector("Drum", "Barrel Veteran", "Drum Captain"),
      Vector("The Stop Sign", "The Stop Sign II"), puddles = false)
  )

  val WavesPerLevel: Vector[Int] = Vector(3, 3, 4, 4, 5, 5, 6, 6, 7, 7)
  val LevelShapes: Vector[String] = Vector("loop", "fork", "snake", "loop", "fork")
  val SmallLevelNames: Vector[String] = Vector("Shoebox Lane", "Tiny Crossing", "Cramped Loop", "Little Fork", "Closet Circuit")

  /** Gold each level starts with; gold does not carry over, and later boards are
    * bigger and busier, so it grows with the level.
    */
  def startGoldFor(level: Int): Int = 120 + 20 * level

  /** A level's waves. The first wave is small and plain (base units and the first
    * rung only); each later wave is bigger and climbs the ladder, and the last wave
    * adds the boss. Later levels start bigger, peak higher and phase out the theme's
    * basic filler unit.
    */
  def wavesFor(theme: Theme, level: Int, boss: String, rng: scala.util.Random): Vector[WaveSpec] =
    val n      = WavesPerLevel(level)
    val ladder = theme.ladder
    val first  = 4 + level / 2
    val peak   = 9 + 3 * level
    (0 until n).toVector.map { j =>
      val f     = if n == 1 then 1.0 else j.toDouble / (n - 1)
      val last  = j == n - 1
      val total = math.round(first + (peak - first) * f).toInt
      val basicShare = math.max(0.0, 0.5 - 0.06 * level)
      val fighting   = if last then total - 1 else total
      val basic = math.round(fighting * basicShare * (1 - f)).toInt
      val t2    = math.round(fighting * 0.35 * f).toInt
      val t3    = math.round(fighting * 0.4 * math.max(0.0, (f - 0.4) / 0.6)).toInt
      val t1    = math.max(0, fighting - basic - t2 - t3)
      val parts = Vector(theme.basic -> basic, ladder(0) -> t1, ladder(1) -> t2, ladder(2) -> t3)
      val comp  = (parts.filter(_._2 > 0) ++ (if last then Vector(boss -> 1) else Vector.empty))
        .groupBy(_._1).toVector.map { case (k, vs) => k -> vs.map(_._2).sum }
        .sortBy { case (name, _) => -comp_rank(name, theme, boss) }
      WaveSpec(
        count = comp.map(_._2).sum,
        spawnInterval = math.round((0.9 - 0.04 * level) * 100).toFloat / 100f,
        hpScale = 1.0f,
        composition = comp
      )
    }

  private def comp_rank(name: String, theme: Theme, boss: String): Int =
    if name == boss then 3 else theme.ladder.indexOf(name) + 1

  /** Rebuilds the global campaign. The first time it takes the five 10x10 routes
    * from the shoebox biome (then drops it) and generates five 14x14 ones; once the
    * campaign exists it keeps the current ten maps and only rebuilds waves, start
    * gold and puddles. Returns the new config, or why it cannot be built.
    */
  def build(cfg: GameConfig, seed: Long): Either[String, GameConfig] =
    val known   = cfg.enemies.map(_.name).toSet
    val needed  = Themes.flatMap(t => t.basic +: t.ladder) ++ Themes.flatMap(_.bosses)
    val missing = needed.filterNot(known.contains).distinct
    if missing.nonEmpty then Left(s"missing enemy types: ${missing.mkString(", ")}")
    else
      val rng = new scala.util.Random(seed)
      val maps: Either[String, Vector[(String, Route, Int)]] =
        cfg.biomes.find(_.id == "shoebox") match
          case Some(sb) =>
            val names     = rng.shuffle(LevelGen.Names)
            val generated = LevelGen.generate(rng, 5, 14, names, 0, LevelShapes)
            Right(sb.levels.take(5).zipWithIndex.map((l, i) => (SmallLevelNames(i), l.route, 10)) ++
              generated.map(g => (g.name, Route(g.waypoints, g.branches), 14)))
          case None if cfg.levels.size == 10 =>
            Right(cfg.levels.map(l => (l.name, l.route, l.gridSize.getOrElse(cfg.board.gridSize))))
          case None => Left("need the shoebox biome or an existing ten-level campaign")
      maps.map { ms =>
        val levels = ms.zipWithIndex.map { case ((name, route, size), i) =>
          val theme = Themes(i / 2)
          val waves = wavesFor(theme, i, theme.bosses(i % 2), rng)
          LevelConfig(name, waves, route, Some(size), puddlesFor(theme, route, size, rng), Some(startGoldFor(i)))
        }
        cfg.copy(levels = levels, biomes = cfg.biomes.filterNot(_.id == "shoebox"))
      }

  /** Water levels get puddles two cells off the road, so shock hits land harder there. */
  private def puddlesFor(theme: Theme, route: Route, size: Int, rng: scala.util.Random): Vector[Puddle] =
    if !theme.puddles then Vector.empty
    else
      val road = route.waypoints.sliding(2).flatMap { case Seq(a, b) => LevelGen.cellsBetween(a, b) }.toVector.distinct
      (0 until 3).toVector.map { _ =>
        val c = road(rng.nextInt(road.size))
        val d = Vector((2, 0), (-2, 0), (0, 2), (0, -2))(rng.nextInt(4))
        val x = math.max(0, math.min(size - 1, c.x + d._1))
        val y = math.max(0, math.min(size - 1, c.y + d._2))
        Puddle(x + 0.5f, y + 0.5f, 1.3f)
      }
