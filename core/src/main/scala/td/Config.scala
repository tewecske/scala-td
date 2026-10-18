package td

import com.badlogic.gdx.graphics.Color

/** Single source of truth for the active tuning. Every old `val` that other
  * modules read globally is now a `def` here, resolving against [[game]]. On
  * reload the backing config is swapped atomically for subsequent steps.
  */
object Config:
  @volatile private var current: GameConfig = ConfigLoader.initial()

  /** The active config. Read this (or the accessors below) instead of caching. */
  def game: GameConfig = current

  /** Reloads from the standard source (working-dir file, else bundled). On
    * failure the previous config is kept and this returns false.
    */
  def reload(): Boolean =
    ConfigLoader.load() match
      case Right(cfg) => current = cfg; true
      case Left(_)    => false

  /** Reload from an explicit path. Same keep-on-failure semantics as [[reload]]. */
  def load(path: String): Boolean =
    ConfigLoader.load(path) match
      case Right(cfg) => current = cfg; true
      case Left(_)    => false

  /** Swaps in an in-memory config without touching disk (balance experiments). */
  def use(cfg: GameConfig): Unit = current = cfg

  // -- active context (per-run board/level/color overrides) ------------------
  // Most of the game is played on the global levels; biomes can override the
  // level list, and levels can override the grid size. The active context is the
  // run's biome and the current level, set by the simulation (and the renderer and
  // input) before any grid math. When unset, the global values are used.

  @volatile private var _activeBiomeId: Option[String] = None
  @volatile private var _activeLevel: Option[Int] = None

  def activeBiomeId: Option[String] = _activeBiomeId

  def setActive(biome: String, level: Int): Unit =
    _activeBiomeId = Some(biome)
    _activeLevel = Some(level)

  def setActiveBiome(id: String): Unit = _activeBiomeId = Some(id)
  def clearActiveBiome(): Unit =
    _activeBiomeId = None
    _activeLevel = None

  /** Runs `f` in the given context, restoring the previous one afterwards. */
  def withContext[A](biome: String, level: Int)(f: => A): A =
    val prevBiome = _activeBiomeId
    val prevLevel = _activeLevel
    setActive(biome, level)
    try f finally
      _activeBiomeId = prevBiome
      _activeLevel = prevLevel

  def withBiome[A](id: String)(f: => A): A =
    val prevBiome = _activeBiomeId
    val prevLevel = _activeLevel
    _activeBiomeId = Some(id)
    _activeLevel = None
    try f finally
      _activeBiomeId = prevBiome
      _activeLevel = prevLevel

  private def activeBiome: Option[Biome] =
    activeBiomeId.flatMap(id => current.biomes.find(_.id == id))

  // -- board -----------------------------------------------------------------
  // The window is fixed: every level's grid divides the same square board.
  /** Cells per side of the given level in the active biome (level, then biome, then board). */
  def gridSizeOf(level: Int): Int =
    levels.lift(level).flatMap(_.gridSize)
      .orElse(activeBiome.flatMap(_.gridSize))
      .getOrElse(current.board.gridSize)
  def GridSize: Int =
    _activeLevel.map(gridSizeOf).getOrElse(
      activeBiome.flatMap(_.gridSize).orElse(levels.headOption.flatMap(_.gridSize)).getOrElse(current.board.gridSize))
  def BoardPx: Float = current.board.boardPx
  def CellPx: Float  = BoardPx / GridSize

  /** Enemy sizes count a tile as this many units, so an enemy covers the same share
    * of a tile on every map, whatever its grid size.
    */
  val EnemyTileUnits: Float = 64f
  /** Board units per enemy-size unit on the active map. */
  def EnemyScale: Float = CellPx / EnemyTileUnits
  def cellPxOf(level: Int): Float = BoardPx / gridSizeOf(level)
  def PanelPx: Float = current.board.panelPx
  def WorldW: Float  = BoardPx + PanelPx
  def WorldH: Float  = BoardPx

  // -- economy ---------------------------------------------------------------
  def StartGold: Int  = current.economy.startGold
  def StartLives: Int = current.economy.startLives
  def KillReward: Int = current.economy.killReward

  // -- meta progression --------------------------------------------------------
  def PayoutPerLevel: Int = current.meta.payoutPerLevel
  def PayoutPerWave: Int  = current.meta.payoutPerWave
  def PayoutWinBonus: Int = current.meta.payoutWinBonus

  // -- draft -----------------------------------------------------------------
  def OfferCount: Int       = current.draft.offerCount
  def RerollCost: Int       = current.draft.rerollCost
  def RerollCostGrowth: Int = current.draft.rerollCostGrowth
  def DraftPoolRounds: Int  = current.draft.poolRounds

  // -- combat ----------------------------------------------------------------
  def HitRadius: Float    = current.combat.hitRadius
  def MaxStepDt: Float    = current.combat.maxStepDt
  def FastForward: Int    = current.combat.fastForward
  def BreakTime: Float    = current.combat.breakTime
  def StudyHits: Int      = current.combat.studyHits
  /** Damage of a critical hit, as a multiple of a normal hit, before talents. */
  def CritMult: Float = current.combat.critMult
  /** Resistance at or above this counts as immune (shown as "immune", no damage number). */
  def ImmuneAt: Float = current.combat.immuneAt

  // -- specs ------------------------------------------------------------------
  def towerSpec(name: String): TowerSpec =
    current.towers.find(_.name == name).getOrElse(sys.error(s"unknown tower '$name'"))
  def enemySpec(name: String): EnemySpec =
    current.enemies.find(_.name == name).getOrElse(sys.error(s"unknown enemy '$name'"))

  // -- biomes -----------------------------------------------------------------
  def biomes: Vector[Biome] = current.biomes
  def biome(id: String): Biome =
    current.biomes.find(_.id == id).getOrElse(sys.error(s"unknown biome '$id'"))

  /** The plain biome every real run's first level is played in, if the config has one. */
  def starterBiome: Option[String] = current.biomes.find(_.starter).map(_.id)

  /** The biome level `level` (0-based) of a run in `runBiome` is played in. The
    * tutorial keeps its own biome throughout.
    */
  def biomeAt(runBiome: String, level: Int): String =
    if level == 0 && runBiome != "tutorial" then starterBiome.getOrElse(runBiome) else runBiome

  // -- twists -----------------------------------------------------------------
  def twists: Vector[Twist] = current.twists
  def twist(id: String): Twist =
    current.twists.find(_.id == id).getOrElse(sys.error(s"unknown twist '$id'"))

  // -- relics -----------------------------------------------------------------
  def relics: Vector[Relic] = current.relics
  def relic(id: String): Relic =
    current.relics.find(_.id == id).getOrElse(sys.error(s"unknown relic '$id'"))

  // -- augments ---------------------------------------------------------------
  def augments: Vector[Augment] = current.augments
  def augment(id: String): Augment =
    current.augments.find(_.id == id).getOrElse(sys.error(s"unknown augment '$id'"))

  // -- talents ----------------------------------------------------------------
  def talents: Vector[Talent] = current.talents
  def talent(id: String): Talent =
    current.talents.find(_.id == id).getOrElse(sys.error(s"unknown talent '$id'"))

  // -- loadout ----------------------------------------------------------------
  def Loadout: LoadoutConfig = current.loadout

  /** The towers a run may build, given its loadout. An empty loadout (used by
    * headless tests and the bot) means unrestricted — every tower is allowed.
    */
  def LoadoutPool(loadout: Vector[String]): Vector[TowerSpec] =
    if loadout.isEmpty then current.towers
    else current.towers.filter(t => loadout.contains(t.name))

  // -- levels -----------------------------------------------------------------
  def levels: Vector[LevelConfig] = activeBiome.filter(_.levels.nonEmpty).map(_.levels).getOrElse(current.levels)
  def levelCount: Int             = levels.size

  /** Gold the player starts the given level with (gold is reset every level). */
  def levelStartGold(idx: Int): Int = levelAt(idx).startGold.getOrElse(StartGold)

  /** Clamped level lookup so the sim can never index out of bounds on a reload
    * or a config that shrank mid-run.
    */
  def levelAt(idx: Int): LevelConfig =
    levels(math.max(0, math.min(idx, levels.size - 1)))

  // -- colors ----------------------------------------------------------------
  // The board palette (background / path / build / grid line) can be overridden
  // per biome; the rest of the palette is shared.

  def Background: Color   = activeBiome.flatMap(_.background).getOrElse(current.colors.background)
  def PathColor: Color    = activeBiome.flatMap(_.path).getOrElse(current.colors.path)
  def BuildColor: Color   = activeBiome.flatMap(_.build).getOrElse(current.colors.build)
  def GridLine: Color     = activeBiome.flatMap(_.gridLine).getOrElse(current.colors.gridLine)
  def EnemyColor: Color   = current.colors.enemy
  def ShotColor: Color    = current.colors.shot
  def ShotFireColor: Color    = current.colors.shotFire
  def ShotFrostColor: Color   = current.colors.shotFrost
  def ShotSplashColor: Color  = current.colors.shotSplash
  def ShotBoomColor: Color    = current.colors.shotBoom
  def BurnTint: Color     = current.colors.burnTint
  def ShotShockColor: Color = current.colors.shotShock
  def SlowTint: Color     = current.colors.slowTint
  def BuffTint: Color     = current.colors.buffTint
  def PanelColor: Color   = current.colors.panel
  def HoverOk: Color      = current.colors.hoverOk
  def HoverBad: Color     = current.colors.hoverBad
  def RangeColor: Color   = current.colors.range
  def ButtonFill: Color   = current.colors.buttonFill
  def HpBarBack: Color    = current.colors.hpBarBack
  def HpBarFill: Color    = current.colors.hpBarFill
  def TextColor: Color    = current.colors.text
  def DimText: Color      = current.colors.dimText

  // -- lighting (ambient-only global illumination) ----------------------------
  def LightingAmbient: Color      = current.lighting.ambient
  def LightingIntensity: Float    = current.lighting.ambientIntensity

  // -- effects ----------------------------------------------------------------
  def VignetteColor: Color   = current.effects.vignette.color
  def VignetteStrength: Float = current.effects.vignette.strength
  def VignetteRadius: Float  = current.effects.vignette.radius

  def SparkColor: Color   = current.effects.sparks.color
  def SparkLife: Float    = current.effects.sparks.life
  def SparkSpeed: Float   = current.effects.sparks.speed
  def SparkSize: Float    = current.effects.sparks.size
  def SparkCount: Int     = current.effects.sparks.count

  def ShadowColor: Color   = current.effects.shadow.color
  def ShadowAlpha: Float   = current.effects.shadow.alpha
  def ShadowOffsetX: Float = current.effects.shadow.offsetX
  def ShadowOffsetY: Float = current.effects.shadow.offsetY
  def ShadowScale: Float   = current.effects.shadow.scale

  def ShakeMagnitude: Float = current.effects.shake.magnitude
  def ShakeDuration: Float  = current.effects.shake.duration
