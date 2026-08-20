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
    val colors  = parseColors(obj(root, "colors"))

    requireUnique(towers.map(_.name), "tower")
    requireUnique(enemies.map(_.name), "enemy")
    val known      = enemies.map(_.name).toSet
    val towerNames = towers.map(_.name).toSet
    val levels     = parseLevels(root, known)
    if levels.isEmpty then throw IllegalArgumentException("at least one level is required")

    val talents = arr(root, "talents").map(parseTalent)
    requireUnique(talents.map(_.id), "talent")
    val talentIds = talents.map(_.id).toSet
    talents.foreach { t =>
      t.prereqs.foreach { p =>
        if !talentIds.contains(p) then
          throw IllegalArgumentException(s"talent '${t.id}' prereq references unknown talent '$p'")
      }
    }

    val biomes = arr(root, "biomes").map(parseBiome(_, known))
    requireUnique(biomes.map(_.id), "biome")
    if biomes.isEmpty then throw IllegalArgumentException("at least one biome is required")
    if !biomes.exists(_.id == "tutorial") then
      throw IllegalArgumentException("a biome with id 'tutorial' is required")

    val twists = arr(root, "twists").map(parseTwist(_, towerNames))
    requireUnique(twists.map(_.id), "twist")

    val relics = arr(root, "relics").map(parseRelic(_, talentIds))
    requireUnique(relics.map(_.id), "relic")

    val augments = arr(root, "augments").map(parseAugment)
    requireUnique(augments.map(_.id), "augment")

    val board = obj(root, "board")
    val econ  = obj(root, "economy")
    val cmbt  = obj(root, "combat")
    val meta  = obj(root, "meta")
    val draft = obj(root, "draft")
    GameConfig(
      board = BoardConfig(i(board, "gridSize"), cellPx, f(board, "panelPx")),
      economy = EconomyConfig(i(econ, "startGold"), i(econ, "startLives"), i(econ, "killReward"), i(econ, "levelBonus")),
      meta = MetaConfig(i(meta, "payoutPerLevel"), i(meta, "payoutPerWave"), i(meta, "payoutWinBonus")),
      draft = DraftConfig(i(draft, "offerCount"), i(draft, "rerollCost"), i(draft, "rerollCostGrowth"), i(draft, "poolRounds")),
      combat = CombatConfig(
        f(cmbt, "maxStepDt"),
        i(cmbt, "fastForward"),
        f(cmbt, "hitRadius"),
        f(cmbt, "burnDps"),
        f(cmbt, "burnDuration"),
        f(cmbt, "slowFactor"),
        f(cmbt, "slowDuration"),
        f(cmbt, "breakTime"),
        i(cmbt, "studyHits")
      ),
      i18n = parseI18n(obj(root, "i18n")),
      towers = towers,
      enemies = enemies,
      levels = levels,
      colors = colors,
      biomes = biomes,
      twists = twists,
      relics = relics,
      augments = augments,
      talents = talents
    )

  private def parseI18n(v: JsonValue): I18nConfig =
    I18nConfig(lang = s(v, "lang"), showCodes = b(v, "showCodes"))

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
      aura = aura,
      heavy = v.getBoolean("heavy", false)
    )

  private def parseLevels(root: JsonValue, known: Set[String]): Vector[LevelConfig] =
    arr(root, "levels").map { v =>
      val name  = s(v, "name")
      val route = parseRoute(obj(v, "route"))
      val waves = arr(v, "waves").map(parseWave(_, known))
      if route.waypoints.isEmpty then
        throw IllegalArgumentException(s"level '$name' has an empty route")
      if waves.isEmpty then
        throw IllegalArgumentException(s"level '$name' has no waves")
      LevelConfig(name, waves, route)
    }

  private def parseWave(v: JsonValue, known: Set[String]): WaveSpec =
    val comp = obj(v, "composition")
    val composition = (0 until comp.size).map { idx =>
      val child = comp.get(idx)
      child.name -> child.asInt()
    }.toVector
    composition.foreach { (name, _) =>
      if !known.contains(name) then
        throw IllegalArgumentException(s"wave composition references unknown enemy '$name'")
    }
    val count = i(v, "count")
    val total = composition.map(_._2).sum
    if count != total then
      throw IllegalArgumentException(s"wave count $count does not match composition total $total")
    WaveSpec(count, f(v, "spawnInterval"), f(v, "hpScale"), composition)

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

  private def parseEffect(v: JsonValue): Effect =
    Effect(kind = s(v, "kind"), params = parseFloatMap(obj(v, "params")))

  private def parseResistTheme(v: JsonValue): ResistTheme =
    ResistTheme(
      primary = enumValue(s(v, "primary"), "resist theme primary", DamageKind.values),
      primaryAmount = f(v, "primaryAmount"),
      secondary = enumValue(s(v, "secondary"), "resist theme secondary", DamageKind.values),
      secondaryAmount = f(v, "secondaryAmount")
    )

  private def parseBiome(v: JsonValue, enemyNames: Set[String]): Biome =
    val id     = s(v, "id")
    val roster = arr(v, "roster").map(_.asString())
    if roster.isEmpty then
      throw IllegalArgumentException(s"biome '$id' has an empty roster")
    roster.foreach { e =>
      if !enemyNames.contains(e) then
        throw IllegalArgumentException(s"biome '$id' roster references unknown enemy '$e'")
    }
    val envVal = v.get("environment")
    val environment =
      if envVal == null || !envVal.isObject then None
      else Some(parseEffect(envVal))
    val theme = parseResistTheme(obj(v, "resistTheme"))
    if theme.primary == theme.secondary then
      throw IllegalArgumentException(s"biome '$id' resist theme primary and secondary must differ")
    Biome(id, name = s(v, "name"), roster, environment, theme)

  private def parseTwist(v: JsonValue, towerNames: Set[String]): Twist =
    val id     = s(v, "id")
    val target = s(v, "targetTower")
    if !towerNames.contains(target) then
      throw IllegalArgumentException(s"twist '$id' targetTower references unknown tower '$target'")
    Twist(id, name = s(v, "name"), targetTower = target, effect = parseEffect(obj(v, "effect")))

  private def parseRelic(v: JsonValue, talentIds: Set[String]): Relic =
    val id     = s(v, "id")
    val rarity = enumValue(s(v, "rarity"), "rarity", Rarity.values)
    val reqVal = v.get("requiredTalent")
    val requiredTalent =
      if reqVal == null || reqVal.isNull then None
      else
        val t = reqVal.asString()
        if !talentIds.contains(t) then
          throw IllegalArgumentException(s"relic '$id' requiredTalent references unknown talent '$t'")
        Some(t)
    if rarity == Rarity.Common && requiredTalent.isDefined then
      throw IllegalArgumentException(s"relic '$id' is Common but has requiredTalent")
    if rarity != Rarity.Common && requiredTalent.isEmpty then
      throw IllegalArgumentException(s"relic '$id' is $rarity but has no requiredTalent")
    Relic(id, name = s(v, "name"), rarity, effect = parseEffect(obj(v, "effect")), requiredTalent)

  private def parseAugment(v: JsonValue): Augment =
    Augment(id = s(v, "id"), name = s(v, "name"), effect = parseEffect(obj(v, "effect")))

  private def parseTalent(v: JsonValue): Talent =
    Talent(
      id = s(v, "id"),
      name = s(v, "name"),
      layer = enumValue(s(v, "layer"), "talent layer", TalentLayer.values),
      cost = i(v, "cost"),
      prereqs = arr(v, "prereqs").map(_.asString()),
      effect = parseEffect(obj(v, "effect"))
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

  private def b(v: JsonValue, name: String): Boolean =
    val child = v.get(name)
    if child == null then throw IllegalArgumentException(s"missing field '$name'")
    child.asBoolean()

  private def c(v: JsonValue, name: String): Color =
    parseColor(s(v, name))

  private def requireUnique(names: Vector[String], what: String): Unit =
    val dupes = names.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }.toVector
    if dupes.nonEmpty then
      throw IllegalArgumentException(s"duplicate $what name(s): ${dupes.mkString(", ")}")

  private def enumValue[T](raw: String, what: String, values: Array[T]): T =
    values.find(_.toString == raw).getOrElse(
      throw IllegalArgumentException(
        s"unknown $what '$raw' (expected one of: ${values.map(_.toString).mkString(", ")})"
      )
    )

  private def parseFloatMap(v: JsonValue): Map[String, Float] =
    (0 until v.size).map { idx =>
      val e = v.get(idx)
      e.name -> e.asFloat()
    }.toMap
