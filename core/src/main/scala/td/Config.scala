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

  // -- board -----------------------------------------------------------------
  def GridSize: Int  = current.board.gridSize
  def CellPx: Float  = current.board.cellPx
  def BoardPx: Float = GridSize * CellPx
  def PanelPx: Float = current.board.panelPx
  def WorldW: Float  = BoardPx + PanelPx
  def WorldH: Float  = BoardPx

  // -- economy ---------------------------------------------------------------
  def StartGold: Int  = current.economy.startGold
  def StartLives: Int = current.economy.startLives
  def KillReward: Int = current.economy.killReward
  def LevelBonus: Int = current.economy.levelBonus

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
  def BurnDps: Float      = current.combat.burnDps
  def BurnDuration: Float = current.combat.burnDuration
  def SlowFactor: Float   = current.combat.slowFactor
  def SlowDuration: Float = current.combat.slowDuration
  def MaxStepDt: Float    = current.combat.maxStepDt
  def FastForward: Int    = current.combat.fastForward
  def BreakTime: Float    = current.combat.breakTime
  def StudyHits: Int      = current.combat.studyHits

  // -- specs ------------------------------------------------------------------
  def towerSpec(name: String): TowerSpec =
    current.towers.find(_.name == name).getOrElse(sys.error(s"unknown tower '$name'"))
  def enemySpec(name: String): EnemySpec =
    current.enemies.find(_.name == name).getOrElse(sys.error(s"unknown enemy '$name'"))

  // -- biomes -----------------------------------------------------------------
  def biomes: Vector[Biome] = current.biomes
  def biome(id: String): Biome =
    current.biomes.find(_.id == id).getOrElse(sys.error(s"unknown biome '$id'"))

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
  def levels: Vector[LevelConfig] = current.levels
  def levelCount: Int             = current.levels.size

  /** Clamped level lookup so the sim can never index out of bounds on a reload
    * or a config that shrank mid-run.
    */
  def levelAt(idx: Int): LevelConfig =
    current.levels(math.max(0, math.min(idx, current.levels.size - 1)))

  // -- colors ----------------------------------------------------------------
  def Background: Color   = current.colors.background
  def PathColor: Color    = current.colors.path
  def BuildColor: Color   = current.colors.build
  def GridLine: Color     = current.colors.gridLine
  def EnemyColor: Color   = current.colors.enemy
  def ShotColor: Color    = current.colors.shot
  def ShotFireColor: Color    = current.colors.shotFire
  def ShotFrostColor: Color   = current.colors.shotFrost
  def ShotSplashColor: Color  = current.colors.shotSplash
  def ShotBoomColor: Color    = current.colors.shotBoom
  def BurnTint: Color     = current.colors.burnTint
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

/** Axis-aligned rectangle in world units, y-up like the rest of libGDX. */
case class Rect(x: Float, y: Float, w: Float, h: Float):
  def contains(px: Float, py: Float): Boolean =
    px >= x && px <= x + w && py >= y && py <= y + h
  def centerX: Float = x + w / 2
  def centerY: Float = y + h / 2

/** Button rectangles, shared by the renderer and the click handler so the thing
  * you see is always the thing you hit.
  */
object Ui:
  private def bw = 220f
  private def bh = 52f
  private def cx = Config.WorldW / 2 - bw / 2

  def Play  = Rect(cx, 300f, bw, bh)
  def Tutorial = Rect(cx, 240f, bw, bh)
  def Talents = Rect(cx, 180f, bw, bh)
  def Retry = Rect(cx, 250f, bw, bh)
  def Menu  = Rect(cx, 180f, bw, bh)
  def PlayAgain = Rect(cx, 250f, bw, bh)
  def Resume = Rect(cx, 250f, bw, bh)
  def Exit   = Rect(cx, 180f, bw, bh)

  /** Pre-run briefing: a BEGIN button and a back-to-menu escape. */
  def BriefingBegin = Rect(cx, 120f, bw, bh)
  def BriefingBack  = Rect(cx, 56f, bw, bh)

  /** Between-level draft: three offer rows plus a reroll button. */
  def DraftOffer(i: Int): Rect = Rect(cx, 420f - i * 80f, bw, 60f)
  def DraftReroll: Rect        = Rect(cx, 120f, bw, bh)

  /** Pre-run loadout picker: one row per tower plus confirm/back buttons. */
  def LoadoutRow(i: Int): Rect = Rect(cx - 200f, 430f - i * 72f, 400f, 56f)
  def LoadoutConfirm: Rect     = Rect(cx, 110f, bw, bh)
  def LoadoutBack: Rect        = Rect(cx, 56f, bw, bh)

  /** Talent screen: fillers / cores / deeps laid out in three columns, ordered
    * by layer. A row is clickable only when it is unlocked and affordable.
    */
  def talentOrder: Vector[Talent] =
    Vector(TalentLayer.Filler, TalentLayer.Core, TalentLayer.Deep)
      .flatMap(l => Config.talents.filter(_.layer == l))

  def TalentRow(i: Int): Rect =
    val t   = talentOrder(i)
    val col = t.layer match
      case TalentLayer.Filler => 0
      case TalentLayer.Core   => 1
      case TalentLayer.Deep   => 2
    val inLayer = talentOrder.zipWithIndex.take(i).count((t2, _) => t2.layer == t.layer)
    val x = 20f + col * 290f
    val y = 470f - inLayer * 64f
    Rect(x, y, 260f, 54f)

  def TalentsBack: Rect = Rect(cx, 56f, bw, bh)
