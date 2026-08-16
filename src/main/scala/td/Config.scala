package td

import com.badlogic.gdx.graphics.Color

/** All tuning lives here. Positions everywhere else are in world units, where
  * one grid cell is [[CellPx]] units wide.
  */
object Config:
  // -- board -----------------------------------------------------------------
  val GridSize = 20
  val CellPx   = 32f
  val BoardPx  = GridSize * CellPx // 640
  val PanelPx  = 240f
  val WorldW   = BoardPx + PanelPx // 880
  val WorldH   = BoardPx           // 640

  // -- economy ---------------------------------------------------------------
  val StartGold  = 120
  val StartLives = 20
  val KillReward = 8

  // -- combat ----------------------------------------------------------------
  // Per-tower tuning (cost, range, damage, fire interval, projectile speed)
  // lives in the TowerType enum; these are the shared status-effect knobs.
  val HitRadius    = 6f
  val BurnDps      = 6f
  val BurnDuration = 3f
  val SlowFactor   = 0.5f
  val SlowDuration = 2f

  /** Frames longer than this are clamped so enemies can never tunnel past a
    * tower after a stall (window drag, GC pause).
    */
  val MaxStepDt = 0.05f

  /** Multiplier applied while fast-forward is toggled on. */
  val FastForward = 3

  // -- colors ----------------------------------------------------------------
  val Background = new Color(0.09f, 0.10f, 0.13f, 1f)
  val PathColor  = new Color(0.44f, 0.41f, 0.37f, 1f)
  val BuildColor = new Color(0.15f, 0.26f, 0.19f, 1f)
  val GridLine   = new Color(0f, 0f, 0f, 0.25f)
  val TowerColor       = new Color(0.35f, 0.60f, 0.95f, 1f)
  val TowerFireColor   = new Color(0.95f, 0.45f, 0.15f, 1f)
  val TowerFrostColor  = new Color(0.45f, 0.85f, 0.95f, 1f)
  val TowerSplashColor = new Color(0.70f, 0.45f, 0.95f, 1f)
  val TowerBoomColor   = new Color(0.80f, 0.25f, 0.20f, 1f)
  val EnemyColor = new Color(0.88f, 0.28f, 0.28f, 1f)
  val ShotColor  = new Color(0.98f, 0.85f, 0.30f, 1f)
  val ShotFireColor    = new Color(0.98f, 0.55f, 0.20f, 1f)
  val ShotFrostColor   = new Color(0.55f, 0.90f, 1f, 1f)
  val ShotSplashColor  = new Color(0.80f, 0.60f, 1f, 1f)
  val ShotBoomColor    = new Color(1f, 0.35f, 0.20f, 1f)
  val BurnTint         = new Color(1f, 0.45f, 0.10f, 1f)
  val SlowTint         = new Color(0.45f, 0.85f, 1f, 1f)
  val PanelColor = new Color(0.13f, 0.14f, 0.18f, 1f)
  val HoverOk    = new Color(0.55f, 0.85f, 0.55f, 0.45f)
  val HoverBad   = new Color(0.85f, 0.30f, 0.30f, 0.40f)
  val RangeColor = new Color(0.35f, 0.60f, 0.95f, 0.55f)
  val ButtonFill = new Color(0.22f, 0.30f, 0.42f, 1f)
  val HpBarBack  = new Color(0.10f, 0.10f, 0.10f, 1f)
  val HpBarFill  = new Color(0.35f, 0.85f, 0.40f, 1f)
  val TextColor  = new Color(0.92f, 0.92f, 0.90f, 1f)
  val DimText    = new Color(0.62f, 0.64f, 0.70f, 1f)

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
  private val bw = 220f
  private val bh = 52f
  private val cx = Config.WorldW / 2 - bw / 2

  val Play  = Rect(cx, 240f, bw, bh)
  val Retry = Rect(cx, 250f, bw, bh)
  val Menu  = Rect(cx, 180f, bw, bh)
