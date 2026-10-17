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

  /** The bundled classpath config, ignoring any working-dir override. */
  def bundled(): Either[String, GameConfig] =
    val in = getClass.getResourceAsStream("/" + FileName)
    if in == null then Left(s"bundled $FileName missing from classpath")
    else parse(Source.fromInputStream(in).mkString)

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
    val towers  = arr(root, "towers").map(parseTower(_, root.get("combat")))
    val enemies = arr(root, "enemies").map(parseEnemy)
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
    // The starter biome hosts every run's first level, so it must be plain.
    val starters = biomes.filter(_.starter)
    if starters.size > 1 then
      throw IllegalArgumentException(s"only one biome may be the starter, got ${starters.map(_.id).mkString(", ")}")
    starters.foreach { b =>
      if b.id == "tutorial" then throw IllegalArgumentException("the tutorial biome cannot be the starter")
      if b.environment.isDefined then
        throw IllegalArgumentException(s"starter biome '${b.id}' must not have an environment rule")
      if b.resistTheme.primaryAmount != 0f || b.resistTheme.secondaryAmount != 0f then
        throw IllegalArgumentException(s"starter biome '${b.id}' must not have resistances")
    }

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
    val loadout = parseLoadout(obj(root, "loadout"), towerNames)
    val lighting = parseLighting(optObj(root, "lighting"))
    val effects = parseEffects(optObj(root, "effects"))
    GameConfig(
      board = BoardConfig(i(board, "gridSize"), fo(board, "boardPx", 640f), f(board, "panelPx")),
      economy = EconomyConfig(i(econ, "startGold"), i(econ, "startLives"), i(econ, "killReward")),
      meta = MetaConfig(i(meta, "payoutPerLevel"), i(meta, "payoutPerWave"), i(meta, "payoutWinBonus")),
      draft = DraftConfig(i(draft, "offerCount"), i(draft, "rerollCost"), i(draft, "rerollCostGrowth"), i(draft, "poolRounds")),
      combat = CombatConfig(
        f(cmbt, "maxStepDt"),
        i(cmbt, "fastForward"),
        f(cmbt, "hitRadius"),
        f(cmbt, "breakTime"),
        i(cmbt, "studyHits"),
        fo(cmbt, "critMult", 1.5f),
        fo(cmbt, "immuneAt", 0.99f)
      ),
      i18n = parseI18n(obj(root, "i18n")),
      towers = towers,
      enemies = enemies,
      levels = levels,
      loadout = loadout,
      colors = colors,
      lighting = lighting,
      effects = effects,
      biomes = biomes,
      twists = twists,
      relics = relics,
      augments = augments,
      talents = talents
    )

  private def parseI18n(v: JsonValue): I18nConfig =
    I18nConfig(lang = s(v, "lang"), showCodes = b(v, "showCodes"))

  private def parseLoadout(v: JsonValue, towerNames: Set[String]): LoadoutConfig =
    val unlocked = arr(v, "unlocked").map(_.asString())
    val locked   = arr(v, "locked").map(_.asString())
    val maxSel   = i(v, "maxSelected")
    val defSel   = arr(v, "defaultSelected").map(_.asString())

    requireUnique(unlocked, "loadout unlocked")
    requireUnique(locked, "loadout locked")
    unlocked.foreach { t =>
      if !towerNames.contains(t) then
        throw IllegalArgumentException(s"loadout unlocked references unknown tower '$t'")
    }
    locked.foreach { t =>
      if !towerNames.contains(t) then
        throw IllegalArgumentException(s"loadout locked references unknown tower '$t'")
    }
    if unlocked.intersect(locked).nonEmpty then
      throw IllegalArgumentException("a tower cannot be both unlocked and locked in loadout")
    if (unlocked ++ locked).toSet != towerNames then
      throw IllegalArgumentException("loadout unlocked + locked must cover every tower")
    if maxSel < 1 then throw IllegalArgumentException("loadout maxSelected must be >= 1")
    if defSel.exists(t => !unlocked.contains(t)) then
      throw IllegalArgumentException("loadout defaultSelected must be a subset of unlocked")
    if defSel.size > maxSel then
      throw IllegalArgumentException("loadout defaultSelected exceeds maxSelected")

    val costsV = v.get("unlockCosts")
    val costs =
      if costsV == null then Map.empty[String, Int]
      else
        var c = costsV.child
        val b = Map.newBuilder[String, Int]
        while c != null do
          if !locked.contains(c.name) then
            throw IllegalArgumentException(s"loadout unlockCosts references '${c.name}', which is not locked")
          if c.asInt() < 0 then throw IllegalArgumentException(s"loadout unlockCosts for '${c.name}' is negative")
          b += c.name -> c.asInt()
          c = c.next
        b.result()

    LoadoutConfig(maxSel, defSel, unlocked, locked, costs)

  /** A tower. Its hit effects (burn, slow, landing spot, puddle bonus) used to come
    * from its damage kind and the combat section; a config written before they
    * were per-tower gets those old values as defaults.
    */
  private def parseTower(v: JsonValue, combat: JsonValue): TowerSpec =
    val kind = DamageKind.valueOf(s(v, "kind"))
    def legacy(name: String, default: Float): Float = if combat == null then default else fo(combat, name, default)
    def when(k: DamageKind, value: Float, otherwise: Float) = if kind == k then value else otherwise
    TowerSpec(
      targeting = Option(v.getString("targeting", null)).fold(Targeting.First)(enumValue(_, "targeting", Targeting.values)),
      burnDps = fo(v, "burnDps", when(DamageKind.Fire, legacy("burnDps", 6f), 0f)),
      burnDuration = fo(v, "burnDuration", when(DamageKind.Fire, legacy("burnDuration", 3f), 0f)),
      slowFactor = fo(v, "slowFactor", when(DamageKind.Slow, legacy("slowFactor", 0.5f), 1f)),
      slowDuration = fo(v, "slowDuration", when(DamageKind.Slow, legacy("slowDuration", 2f), 0f)),
      groundTarget = v.getBoolean("groundTarget", kind == DamageKind.Explosion),
      puddleMult = fo(v, "puddleMult", when(DamageKind.Shock, legacy("puddleShockMult", 1.5f), 1f)),
      name = s(v, "name"),
      cost = i(v, "cost"),
      color = parseColor(s(v, "color")),
      range = f(v, "range"),
      fireInterval = f(v, "fireInterval"),
      damage = f(v, "damage"),
      projectileSpeed = f(v, "projectileSpeed"),
      kind = kind,
      splashRadius = f(v, "splashRadius")
    )

  private def parseEnemy(v: JsonValue): EnemySpec =
    val resist = obj(v, "resist")
    val auraVal = v.get("aura")
    val aura =
      if auraVal == null || !auraVal.isObject then None
      else
        Some(
          Aura(
            AuraKind.valueOf(auraVal.getString("kind")),
            auraVal.getFloat("radius"),
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
        f(resist, "explosion"),
        fo(resist, "shock", 0f)
      ),
      aura = aura,
      heavy = v.getBoolean("heavy", false),
      sidePreference =
        val sideVal = v.get("sidePreference")
        if sideVal == null || sideVal.isNull then None
        else Some(enumValue(sideVal.asString(), "side preference", RouteSide.values)),
      killReward = fo(v, "killReward", 1f),
      speedVariance = fo(v, "speedVariance", 0f),
      boss = v.getBoolean("boss", false),
      onDeath = optOnDeath(v),
      promotesTo = optString(v, "promotesTo"),
      leakCost = optInt(v, "leakCost").getOrElse(1),
      shape = optString(v, "shape").fold(EnemyShape.Square)(s => enumValue(s, "enemy shape", EnemyShape.values))
    )

  private def optString(v: JsonValue, name: String): Option[String] =
    val child = v.get(name)
    if child == null || child.isNull then None else Some(child.asString())

  private def optOnDeath(v: JsonValue): Option[(String, Int)] =
    val child = v.get("onDeath")
    if child == null || !child.isObject then None
    else Some(s(child, "enemy") -> i(child, "count"))

  private def parsePuddles(v: JsonValue): Vector[Puddle] =
    val child = v.get("puddles")
    if child == null || !child.isArray then Vector.empty
    else (0 until child.size).map { idx =>
      val p = child.get(idx)
      Puddle(f(p, "x"), f(p, "y"), f(p, "radius"))
    }.toVector

  private def optInt(v: JsonValue, name: String): Option[Int] =
    val child = v.get(name)
    if child == null || child.isNull then None else Some(child.asInt())

  private def parseLevels(root: JsonValue, known: Set[String]): Vector[LevelConfig] =
    arr(root, "levels").map { v =>
      val name  = s(v, "name")
      val route = parseRoute(obj(v, "route"))
      val waves = arr(v, "waves").map(parseWave(_, known))
      if route.waypoints.isEmpty then
        throw IllegalArgumentException(s"level '$name' has an empty route")
      if route.branches.nonEmpty && route.waypoints.size < 2 then
        throw IllegalArgumentException(s"level '$name' has branches but fewer than two waypoints")
      route.branches.foreach { b =>
        if b.from < 0 || b.from >= route.waypoints.size then
          throw IllegalArgumentException(s"level '$name' branch '${b.side}' from index ${b.from} out of range")
        if b.to <= b.from || b.to >= route.waypoints.size then
          throw IllegalArgumentException(s"level '$name' branch '${b.side}' needs 0 < to > from < waypoints")
        if b.waypoints.isEmpty then
          throw IllegalArgumentException(s"level '$name' branch '${b.side}' has no waypoints")
      }
      val dupFrom = route.branches.groupBy(_.from).collect { case (f, bs) if bs.size > 1 => f }
      if dupFrom.nonEmpty then
        throw IllegalArgumentException(s"level '$name' has multiple branches at fork index ${dupFrom.min}")
      if waves.isEmpty then
        throw IllegalArgumentException(s"level '$name' has no waves")
      LevelConfig(name, waves, route, optInt(v, "gridSize"), parsePuddles(v), optInt(v, "startGold"))
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
    val branchesVal = v.get("branches")
    val branches =
      if branchesVal == null || !branchesVal.isArray then Vector.empty
      else (0 until branchesVal.size).map { idx =>
        val b = branchesVal.get(idx)
        Branch(
          side = enumValue(s(b, "side"), "branch side", RouteSide.values),
          from = i(b, "from"),
          to = i(b, "to"),
          waypoints = arr(b, "waypoints").map { p =>
            GridPos(p.get(0).asInt(), p.get(1).asInt())
          }
        )
      }.toVector
    Route(waypoints, branches)

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
      dimText = c(v, "dimText"),
      shotShock = if v.get("shotShock") == null then Color(0.5f, 0.85f, 1f, 1f) else c(v, "shotShock")
    )

  /** Ambient-only global illumination. Optional; defaults keep the current look. */
  private def parseLighting(v: JsonValue): LightingConfig =
    if v == null then LightingConfig(ambient = Color.WHITE, ambientIntensity = 1f)
    else LightingConfig(ambient = c(v, "ambient"), ambientIntensity = fo(v, "ambientIntensity", 1f))

  private def parseEffects(v: JsonValue): EffectsConfig =
    if v == null then EffectsConfig(
      vignette = VignetteConfig(Color.BLACK, 0.35f, 0.85f),
      sparks   = SparksConfig(Color(1f, 0.9f, 0.65f, 1f), 0.5f, 120f, 3f, 6),
      shadow   = ShadowConfig(Color.BLACK, 0.35f, 3f, -3f, 1.1f),
      shake    = ShakeConfig(6f, 0.25f)
    )
    else EffectsConfig(
      vignette = parseVignette(optObj(v, "vignette")),
      sparks   = parseSparks(optObj(v, "sparks")),
      shadow   = parseShadow(optObj(v, "shadow")),
      shake    = parseShake(optObj(v, "shake"))
    )

  private def parseVignette(v: JsonValue): VignetteConfig =
    if v == null then VignetteConfig(Color.BLACK, 0.35f, 0.85f)
    else VignetteConfig(c(v, "color"), fo(v, "strength", 0.35f), fo(v, "radius", 0.85f))

  private def parseSparks(v: JsonValue): SparksConfig =
    if v == null then SparksConfig(Color(1f, 0.9f, 0.65f, 1f), 0.5f, 120f, 3f, 6)
    else SparksConfig(c(v, "color"), fo(v, "life", 0.5f), fo(v, "speed", 120f),
      fo(v, "size", 3f), i(v, "count"))

  private def parseShadow(v: JsonValue): ShadowConfig =
    if v == null then ShadowConfig(Color.BLACK, 0.35f, 3f, -3f, 1.1f)
    else ShadowConfig(c(v, "color"), fo(v, "alpha", 0.35f), fo(v, "offsetX", 3f),
      fo(v, "offsetY", -3f), fo(v, "scale", 1.1f))

  private def parseShake(v: JsonValue): ShakeConfig =
    if v == null then ShakeConfig(6f, 0.25f)
    else ShakeConfig(fo(v, "magnitude", 6f), fo(v, "duration", 0.25f))

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
    val gridSize  = v.get("gridSize")
    val gridSizeOpt = if gridSize == null then None else Some(gridSize.asInt())
    val hasLevels = v.get("levels")
    val levels =
      if hasLevels == null || !hasLevels.isArray then Vector.empty
      else arr(v, "levels").map { lv =>
        val name  = s(lv, "name")
        val route = parseRoute(obj(lv, "route"))
        val waves = arr(lv, "waves").map(parseWave(_, enemyNames))
        if route.waypoints.isEmpty then
          throw IllegalArgumentException(s"biome '$id' level '$name' has an empty route")
        if waves.isEmpty then
          throw IllegalArgumentException(s"biome '$id' level '$name' has no waves")
        LevelConfig(name, waves, route, optInt(lv, "gridSize"), parsePuddles(lv), optInt(lv, "startGold"))
      }
    val colorOpt = (name: String) =>
      val c = v.get(name)
      if c == null then None else Some(parseColor(c.asString()))
    Biome(
      id,
      name = s(v, "name"),
      roster,
      environment,
      theme,
      gridSize = gridSizeOpt,
      levels = levels,
      background = colorOpt("background"),
      path = colorOpt("path"),
      build = colorOpt("build"),
      gridLine = colorOpt("gridLine"),
      starter = v.getBoolean("starter", false)
    )

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

  /** Like [[obj]] but returns null when the field is absent, so callers can
    * fall back to defaults (used for the optional lighting/effects blocks).
    */
  private def optObj(v: JsonValue, name: String): JsonValue =
    val child = v.get(name)
    if child == null || !child.isObject then null else child

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

  private def fo(v: JsonValue, name: String, default: Float): Float =
    val child = v.get(name)
    if child == null then default else child.asFloat()

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
