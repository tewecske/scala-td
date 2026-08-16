package td

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.utils.{JsonReader, JsonValue}
import java.io.File
import scala.io.Source

/** Reads tuning from JSON. The bundled `config.json` on the classpath is the
  * default; a `config.json` in the process working directory, if present, wins.
  * Parsing is done with libGDX's `JsonReader` (pure JSON, no GL) and is safe to
  * call headlessly.
  */
object ConfigLoader:

  val FileName = "config.json"

  /** Loads the default/working-dir config, throwing if it can't be parsed. Used
    * once at startup; reloads go through [[Config.reload]] so a bad config never
    * leaves the game unconfigured.
    */
  def initial(): GameConfig =
    load().fold(err => throw new RuntimeException(s"could not load config: $err"), identity)

  /** Standard source: working-dir file if present, else the bundled resource. */
  def load(): Either[String, GameConfig] =
    readSource() match
      case Left(err)   => Left(err)
      case Right(text) => parse(text)

  /** Load from an explicit path (used by tests and could back a "pick config"
    * menu later).
    */
  def load(path: String): Either[String, GameConfig] =
    val f = File(path)
    if !f.isFile then Left(s"config file not found: $path")
    else parse(Source.fromFile(f).mkString)

  def readSource(): Either[String, String] =
    val f = File(FileName)
    if f.isFile then Right(Source.fromFile(f).mkString)
    else
      val in = getClass.getResourceAsStream("/" + FileName)
      if in == null then Left(s"bundled $FileName missing from classpath")
      else Right(Source.fromInputStream(in).mkString)

  // -- color ------------------------------------------------------------------

  /** `#RRGGBB` or `#RRGGBBAA` -> a `Color` (channels are 0..255). */
  def parseColor(hex: String): Color =
    val h = if hex.startsWith("#") then hex.substring(1) else hex
    if h.length != 6 && h.length != 8 then
      throw IllegalArgumentException(s"bad color '$hex' (want #RRGGBB or #RRGGBBAA)")
    try
      val r = Integer.parseInt(h.substring(0, 2), 16) / 255f
      val g = Integer.parseInt(h.substring(2, 4), 16) / 255f
      val b = Integer.parseInt(h.substring(4, 6), 16) / 255f
      val a = if h.length == 8 then Integer.parseInt(h.substring(6, 8), 16) / 255f else 1f
      Color(r, g, b, a)
    catch case _: NumberFormatException => throw IllegalArgumentException(s"bad color '$hex'")

  // -- parsing ----------------------------------------------------------------

  def parse(text: String): Either[String, GameConfig] =
    try
      val root = JsonReader().parse(text)
      Right(parseRoot(root))
    catch case e: Exception => Left(s"config parse error: ${e.getMessage}")

  private def parseRoot(root: JsonValue): GameConfig =
    val cellPx  = f(obj(root, "board"), "cellPx")
    val towers  = arr(root, "towers").map(parseTower(_, cellPx))
    val enemies = arr(root, "enemies").map(parseEnemy(_, cellPx))
    val waves   = parseWaves(obj(root, "waves"))
    val colors  = parseColors(obj(root, "colors"))
    val route   = parseRoute(obj(root, "route"))

    requireUnique(towers.map(_.name), "tower")
    requireUnique(enemies.map(_.name), "enemy")
    val known = enemies.map(_.name).toSet
    waves.composition.foreach { entry =>
      entry.weights.foreach { (name, _) =>
        if !known.contains(name) then
          throw IllegalArgumentException(s"wave composition references unknown enemy '$name'")
      }
    }

    val board = obj(root, "board")
    val econ  = obj(root, "economy")
    val cmbt  = obj(root, "combat")
    GameConfig(
      board = BoardConfig(i(board, "gridSize"), cellPx, f(board, "panelPx")),
      economy = EconomyConfig(i(econ, "startGold"), i(econ, "startLives"), i(econ, "killReward")),
      combat = CombatConfig(
        f(cmbt, "maxStepDt"),
        i(cmbt, "fastForward"),
        f(cmbt, "hitRadius"),
        f(cmbt, "burnDps"),
        f(cmbt, "burnDuration"),
        f(cmbt, "slowFactor"),
        f(cmbt, "slowDuration")
      ),
      towers = towers,
      enemies = enemies,
      waves = waves,
      route = route,
      colors = colors
    )

  private def parseTower(v: JsonValue, cellPx: Float): TowerSpec =
    TowerSpec(
      name = s(v, "name"),
      cost = i(v, "cost"),
      color = parseColor(s(v, "color")),
      range = f(v, "range") * cellPx,
      fireInterval = f(v, "fireInterval"),
      damage = f(v, "damage"),
      projectileSpeed = f(v, "projectileSpeed"),
      kind = DamageKind.valueOf(s(v, "kind")),
      splashRadius = f(v, "splashRadius") * cellPx
    )

  private def parseEnemy(v: JsonValue, cellPx: Float): EnemySpec =
    val resist = obj(v, "resist")
    val auraVal = v.get("aura")
    val aura =
      if auraVal == null || !auraVal.isObject then None
      else
        Some(
          Aura(
            AuraKind.valueOf(auraVal.getString("kind")),
            auraVal.getFloat("radius") * cellPx,
            auraVal.getFloat("magnitude")
          )
        )
    EnemySpec(
      name = s(v, "name"),
      baseHp = f(v, "baseHp"),
      baseSpeed = f(v, "baseSpeed"),
      color = parseColor(s(v, "color")),
      size = f(v, "size"),
      resist = Resistances(
        f(resist, "kinetic"),
        f(resist, "fire"),
        f(resist, "slow"),
        f(resist, "splash"),
        f(resist, "explosion")
      ),
      aura = aura
    )

  private def parseWaves(v: JsonValue): WaveConfig =
    val composition = arr(v, "composition").map { entry =>
      val w = obj(entry, "weights")
      val weights = (0 until w.size).map { idx =>
        val child = w.get(idx)
        child.name -> child.asInt()
      }.toVector
      CompositionEntry(i(entry, "fromWave"), weights)
    }
    WaveConfig(
      breakTime = f(v, "breakTime"),
      hpScaleBase = f(v, "hpScaleBase"),
      hpScaleExp = f(v, "hpScaleExp"),
      countBase = i(v, "countBase"),
      countPerWave = i(v, "countPerWave"),
      spawnIntervalStart = f(v, "spawnIntervalStart"),
      spawnIntervalDecay = f(v, "spawnIntervalDecay"),
      spawnIntervalMin = f(v, "spawnIntervalMin"),
      composition = composition
    )

  private def parseRoute(v: JsonValue): Route =
    val waypoints = arr(v, "waypoints").map { p =>
      GridPos(p.get(0).asInt(), p.get(1).asInt())
    }
    Route(waypoints)

  private def parseColors(v: JsonValue): Colors =
    Colors(
      background = c(v, "background"),
      path = c(v, "path"),
      build = c(v, "build"),
      gridLine = c(v, "gridLine"),
      enemy = c(v, "enemy"),
      shot = c(v, "shot"),
      shotFire = c(v, "shotFire"),
      shotFrost = c(v, "shotFrost"),
      shotSplash = c(v, "shotSplash"),
      shotBoom = c(v, "shotBoom"),
      burnTint = c(v, "burnTint"),
      slowTint = c(v, "slowTint"),
      buffTint = c(v, "buffTint"),
      panel = c(v, "panel"),
      hoverOk = c(v, "hoverOk"),
      hoverBad = c(v, "hoverBad"),
      range = c(v, "range"),
      buttonFill = c(v, "buttonFill"),
      hpBarBack = c(v, "hpBarBack"),
      hpBarFill = c(v, "hpBarFill"),
      text = c(v, "text"),
      dimText = c(v, "dimText")
    )

  // -- helpers ----------------------------------------------------------------

  private def obj(v: JsonValue, name: String): JsonValue =
    val child = v.get(name)
    if child == null || !child.isObject then
      throw IllegalArgumentException(s"missing object '$name'")
    child

  private def arr(v: JsonValue, name: String): Vector[JsonValue] =
    val child = v.get(name)
    if child == null || !child.isArray then
      throw IllegalArgumentException(s"missing array '$name'")
    (0 until child.size).map(child.get(_)).toVector

  private def s(v: JsonValue, name: String): String =
    val child = v.get(name)
    if child == null then throw IllegalArgumentException(s"missing field '$name'")
    child.asString()

  private def f(v: JsonValue, name: String): Float =
    val child = v.get(name)
    if child == null then throw IllegalArgumentException(s"missing field '$name'")
    child.asFloat()

  private def i(v: JsonValue, name: String): Int =
    val child = v.get(name)
    if child == null then throw IllegalArgumentException(s"missing field '$name'")
    child.asInt()

  private def c(v: JsonValue, name: String): Color =
    parseColor(s(v, name))

  private def requireUnique(names: Vector[String], what: String): Unit =
    val dupes = names.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }.toVector
    if dupes.nonEmpty then
      throw IllegalArgumentException(s"duplicate $what name(s): ${dupes.mkString(", ")}")
