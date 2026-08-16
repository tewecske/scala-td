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

  // -- combat ----------------------------------------------------------------
  def HitRadius: Float    = current.combat.hitRadius
  def BurnDps: Float      = current.combat.burnDps
  def BurnDuration: Float = current.combat.burnDuration
  def SlowFactor: Float   = current.combat.slowFactor
  def SlowDuration: Float = current.combat.slowDuration
  def MaxStepDt: Float    = current.combat.maxStepDt
  def FastForward: Int    = current.combat.fastForward

  // -- specs ------------------------------------------------------------------
  def towerSpec(name: String): TowerSpec =
    current.towers.find(_.name == name).getOrElse(sys.error(s"unknown tower '$name'"))
  def enemySpec(name: String): EnemySpec =
    current.enemies.find(_.name == name).getOrElse(sys.error(s"unknown enemy '$name'"))

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

  def Play  = Rect(cx, 240f, bw, bh)
  def Retry = Rect(cx, 250f, bw, bh)
  def Menu  = Rect(cx, 180f, bw, bh)
