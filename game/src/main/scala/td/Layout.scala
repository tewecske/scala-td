package td

/** Axis-aligned rectangle in UI units, y-up like the rest of libGDX. */
final case class Rect(x: Float, y: Float, w: Float, h: Float):
  def contains(px: Float, py: Float): Boolean =
    px >= x && px <= x + w && py >= y && py <= y + h
  def centerX: Float = x + w / 2
  def centerY: Float = y + h / 2
  def right: Float   = x + w
  def top: Float     = y + h
  def inset(d: Float): Rect = Rect(x + d, y + d, w - 2 * d, h - 2 * d)

/** Where everything goes, in UI units. The window shows at least a
  * [[RefW]] x [[RefH]] canvas, scaled uniformly; extra width or height (other
  * aspect ratios) extends the canvas, and [[W]] / [[H]] hold its current size.
  * The renderer draws these rectangles and the input code hit-tests the same
  * ones, so the thing you see is always the thing you click.
  *
  * The board keeps one tile size for every map: the largest map in the config
  * just fits the board area, and smaller maps are centered in it.
  */
object Layout:
  val RefW = 1920f
  val RefH = 1080f

  @volatile var W: Float = RefW
  @volatile var H: Float = RefH

  def cx: Float = W / 2

  // -- shared pieces ------------------------------------------------------------

  val ButtonH  = 64f
  val ButtonW  = 300f

  /** Baseline of a screen's heading and the line under it. */
  def titleY: Float    = H - 110f
  def subtitleY: Float = H - 175f

  /** `n` buttons side by side at the bottom of the screen. */
  def bottomRow(n: Int, i: Int, y: Float = 56f): Rect =
    val gap   = 28f
    val total = n * ButtonW + (n - 1) * gap
    Rect(cx - total / 2 + i * (ButtonW + gap), y, ButtonW, ButtonH)

  // -- main menu ----------------------------------------------------------------

  enum MenuItem:
    case Play, Tutorial, Talents, Replays, Progress, Settings, Quit

  def menuButton(item: MenuItem): Rect =
    val i   = item.ordinal
    val w   = 440f
    val top = H - 380f
    Rect(cx - w / 2, top - i * (ButtonH + 14f), w, ButtonH)

  // -- settings -------------------------------------------------------------------

  /** One settings row: label on the left, a value between two arrows on the right. */
  def settingsRow(i: Int): Rect = Rect(cx - 460f, H - 330f - i * 104f, 920f, 84f)
  def settingsPrev(i: Int): Rect =
    val r = settingsRow(i)
    Rect(r.x + 390f, r.y + 12f, 60f, 60f)
  def settingsNext(i: Int): Rect =
    val r = settingsRow(i)
    Rect(r.right - 72f, r.y + 12f, 60f, 60f)
  def settingsValue(i: Int): Rect =
    val r = settingsRow(i)
    Rect(settingsPrev(i).right, r.y, settingsNext(i).x - settingsPrev(i).right, r.h)
  def settingsApply: Rect = bottomRow(2, 0)
  def settingsBack: Rect  = bottomRow(2, 1)

  // -- talents --------------------------------------------------------------------

  private val TalentColW = 360f
  private val TalentGap  = 32f
  private def talentX(col: Int): Float = cx - (4 * TalentColW + 3 * TalentGap) / 2 + col * (TalentColW + TalentGap)
  def talentHeaderY: Float = H - 250f

  /** Talents in three columns (filler / core / deep); the fourth holds the effects. */
  def talentOrder: Vector[Talent] =
    Vector(TalentLayer.Filler, TalentLayer.Core, TalentLayer.Deep)
      .flatMap(l => Config.talents.filter(_.layer == l))

  def talentColumn(layer: TalentLayer): Int = layer match
    case TalentLayer.Filler => 0
    case TalentLayer.Core   => 1
    case TalentLayer.Deep   => 2

  /** Rows get closer together when a column holds more talents than fit at full spacing. */
  private def talentStep: Float =
    val rows = talentOrder.groupBy(_.layer).values.map(_.size).maxOption.getOrElse(1)
    if rows <= 1 then 84f else math.min(84f, (talentHeaderY - 110f - 150f) / (rows - 1))

  def talentRow(i: Int): Rect =
    val t       = talentOrder(i)
    val inLayer = talentOrder.take(i).count(_.layer == t.layer)
    val step    = talentStep
    Rect(talentX(talentColumn(t.layer)), talentHeaderY - 110f - inLayer * step, TalentColW, math.min(72f, step - 8f))

  def talentEffects: Rect =
    val top = talentHeaderY - 38f
    Rect(talentX(3), top - 520f, TalentColW, 520f)
  def talentColumnX(col: Int): Float = talentX(col)
  def talentColumnW: Float = TalentColW
  def talentsBack: Rect = bottomRow(1, 0)

  // -- briefing / loadout ---------------------------------------------------------------

  def briefingArt: Rect   = Rect(cx - 380f, 250f, 760f, 470f)
  def briefingBegin: Rect = bottomRow(2, 1)
  def briefingBack: Rect  = bottomRow(2, 0)

  def loadoutRow(i: Int): Rect = Rect(cx - 380f, H - 300f - i * 84f, 760f, 72f)
  def loadoutConfirm: Rect     = bottomRow(2, 1)
  def loadoutBack: Rect        = bottomRow(2, 0)

  // -- lists (replays, progress) ---------------------------------------------------

  val ListPerPage: Int = 8
  def listPages(count: Int): Int = math.max(1, (count + ListPerPage - 1) / ListPerPage)
  def listRow(i: Int): Rect = Rect(cx - 560f, H - 290f - i * 80f, 1120f, 68f)
  def listPrev: Rect = bottomRow(3, 0)
  def listBack: Rect = bottomRow(3, 1)
  def listNext: Rect = bottomRow(3, 2)
  def listPageY: Float = 170f

  // -- draft / pause / recap ---------------------------------------------------------

  def draftCard(i: Int, n: Int): Rect =
    val w = 420f; val gap = 40f
    val total = n * w + (n - 1) * gap
    Rect(cx - total / 2 + i * (w + gap), H / 2 - 170f, w, 360f)
  def draftReroll: Rect = bottomRow(1, 0, y = 120f)

  def pausePanel: Rect = Rect(cx - 280f, H / 2 - 220f, 560f, 440f)
  def pauseButton(i: Int): Rect =
    val p = pausePanel
    Rect(p.centerX - 180f, p.top - 190f - i * 84f, 360f, ButtonH)

  def recapRetry: Rect = bottomRow(2, 1)
  def recapMenu: Rect  = bottomRow(2, 0)

  // -- in game --------------------------------------------------------------------------

  val PanelW = 480f
  val Margin = 36f

  def panel: Rect = Rect(W - PanelW, 0f, PanelW, H)

  /** The space the board is centered in: everything left of the side panel. */
  def boardArea: Rect = Rect(Margin, Margin, W - PanelW - 2 * Margin, H - 2 * Margin)

  /** Every grid size any level uses, for picking the one tile size they all fit. */
  private var sizesFor: (GameConfig, Int) = (null, 0)
  def largestGrid: Int =
    val cfg = Config.game
    if sizesFor._1 ne cfg then
      val sizes =
        for
          biome <- cfg.biomes.map(_.id)
          level <- 0 until Config.withBiome(biome)(Config.levelCount)
        yield Config.withBiome(biome)(Config.gridSizeOf(level))
      sizesFor = (cfg, (sizes :+ 1).max)
    sizesFor._2

  /** Side of one board tile in UI units; the same on every map. */
  def tile: Float =
    val a = boardArea
    math.floor(math.min(a.w, a.h) / largestGrid).toFloat

  /** The board of the active level, centered in the board area. */
  def board: Rect =
    val a    = boardArea
    val side = Config.GridSize * tile
    Rect(a.centerX - side / 2, a.centerY - side / 2, side, side)

  /** UI units per simulation unit on the active level (the sim's cells are
    * `Config.CellPx` wide; on screen they are [[tile]] wide).
    */
  def boardScale: Float = tile / Config.CellPx

  /** Simulation coordinates -> UI units, for the active level. */
  def toUi(x: Float, y: Float): (Float, Float) =
    val b = board
    (b.x + x * boardScale, b.y + y * boardScale)

  /** UI units -> simulation coordinates, for the active level. */
  def toSim(ux: Float, uy: Float): (Float, Float) =
    val b = board
    ((ux - b.x) / boardScale, (uy - b.y) / boardScale)

  /** HUD: the buildable towers, one clickable row each. */
  def hudTower(i: Int, top: Float): Rect = Rect(panel.x + 24f, top - (i + 1) * 62f, PanelW - 48f, 54f)

  /** Replay controls at the bottom of the side panel. */
  private def replayX: Float = panel.x + 28f
  private def replayW: Float = PanelW - 56f
  def replayProgress: Rect = Rect(replayX, 252f, replayW, 14f)
  def replaySlower: Rect   = Rect(replayX, 156f, 100f, 58f)
  def replayPause: Rect    = Rect(replayX + 112f, 156f, replayW - 224f, 58f)
  def replayFaster: Rect   = Rect(replayX + replayW - 100f, 156f, 100f, 58f)
  def replayExit: Rect     = Rect(replayX, 84f, replayW, 58f)
  /** Lowest point the HUD's text may reach above the replay controls. */
  def replayTop: Float = 350f

  // -- developer overlay ------------------------------------------------------------

  def devPanel: Rect = Rect(W - PanelW - 300f, H - 360f, 280f, 330f)
  enum DevItem:
    case Gold100, Gold1000, Meta100, UnlockTowers, UnlockTalents
  def devButton(item: DevItem): Rect =
    val p = devPanel
    Rect(p.x + 16f, p.top - 92f - item.ordinal * 52f, p.w - 32f, 44f)
