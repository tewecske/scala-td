package td

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.{Color, GL20, OrthographicCamera}
import com.badlogic.gdx.graphics.g2d.{BitmapFont, GlyphLayout, SpriteBatch}
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport

/** Everything the player sees. Squares via ShapeRenderer, text via libGDX's
  * built-in font — no asset files anywhere.
  */
class Render:
  private val camera   = new OrthographicCamera()
  private val viewport = new FitViewport(Config.WorldW, Config.WorldH, camera)
  private val shapes   = new ShapeRenderer()
  private val batch    = new SpriteBatch()
  private val font     = new BitmapFont()
  private val layout   = new GlyphLayout()
  private val scratch  = new Vector2()

  font.setUseIntegerPositions(false)

  def resize(w: Int, h: Int): Unit = viewport.update(w, h, true)

  /** Screen pixel -> world unit, so clicks land where they look like they land. */
  def toWorld(screenX: Int, screenY: Int): (Float, Float) =
    val v = viewport.unproject(scratch.set(screenX.toFloat, screenY.toFloat))
    (v.x, v.y)

  def dispose(): Unit =
    shapes.dispose()
    batch.dispose()
    font.dispose()

  // -- frame scaffolding -----------------------------------------------------

  private def begin(): Unit =
    ScreenUtils.clear(Config.Background)
    viewport.apply()
    Gdx.gl.glEnable(GL20.GL_BLEND)
    shapes.setProjectionMatrix(camera.combined)
    batch.setProjectionMatrix(camera.combined)

  private def box(r: Rect, c: Color): Unit =
    shapes.setColor(c)
    shapes.rect(r.x, r.y, r.w, r.h)

  private def text(s: String, x: Float, y: Float, scale: Float = 1f, c: Color = Config.TextColor): Unit =
    font.getData.setScale(scale)
    font.setColor(c)
    font.draw(batch, s, x, y)

  private def centered(s: String, cx: Float, y: Float, scale: Float = 1f, c: Color = Config.TextColor): Unit =
    font.getData.setScale(scale)
    font.setColor(c)
    layout.setText(font, s)
    font.draw(batch, layout, cx - layout.width / 2, y)

  private def button(r: Rect): Unit =
    box(r, Config.ButtonFill)

  private def buttonLabel(r: Rect, label: String): Unit =
    centered(label, r.centerX, r.centerY + 8f, 1.4f)

  // -- screens ---------------------------------------------------------------

  def drawMenu(): Unit =
    begin()
    shapes.begin(ShapeType.Filled)
    button(Ui.Play)
    shapes.end()

    batch.begin()
    centered("TOWER DEFENSE", Config.WorldW / 2, 440f, 3f)
    centered("20 x 20 · hold the line", Config.WorldW / 2, 390f, 1.2f, Config.DimText)
    buttonLabel(Ui.Play, "PLAY")
    centered("1-5 to pick a tower · click green to build · F to fast-forward · ESC to quit a run",
      Config.WorldW / 2, 120f, 1f, Config.DimText)
    batch.end()

  def drawGameOver(wave: Int): Unit =
    begin()
    shapes.begin(ShapeType.Filled)
    button(Ui.Retry)
    button(Ui.Menu)
    shapes.end()

    batch.begin()
    centered("GAME OVER", Config.WorldW / 2, 460f, 3f, Config.EnemyColor)
    centered(s"you reached wave $wave", Config.WorldW / 2, 400f, 1.4f, Config.DimText)
    buttonLabel(Ui.Retry, "RETRY")
    buttonLabel(Ui.Menu, "MENU")
    batch.end()

  def drawGame(w: World, hover: Option[GridPos], fast: Boolean, selected: TowerType): Unit =
    begin()

    shapes.begin(ShapeType.Filled)
    drawBoard()
    drawTowers(w)
    drawEnemies(w)
    drawShots(w)
    drawHover(w, hover, selected)
    shapes.setColor(Config.PanelColor)
    shapes.rect(Config.BoardPx, 0f, Config.PanelPx, Config.WorldH)
    shapes.end()

    shapes.begin(ShapeType.Line)
    drawRanges(w, hover, selected)
    shapes.end()

    batch.begin()
    drawHud(w, fast, selected)
    batch.end()

  // -- board pieces ----------------------------------------------------------

  /** Cells are drawn inset by one unit, which leaves the dark background
    * showing through as grid lines for free.
    */
  private def drawBoard(): Unit =
    for
      gx <- 0 until Config.GridSize
      gy <- 0 until Config.GridSize
    do
      val cell = GridPos(gx, gy)
      shapes.setColor(if Grid.pathCells.contains(cell) then Config.PathColor else Config.BuildColor)
      shapes.rect(gx * Config.CellPx + 1f, gy * Config.CellPx + 1f, Config.CellPx - 2f, Config.CellPx - 2f)

  private def drawTowers(w: World): Unit =
    w.towers.foreach { t =>
      shapes.setColor(t.tpe.color)
      shapes.rect(
        t.cell.x * Config.CellPx + 5f,
        t.cell.y * Config.CellPx + 5f,
        Config.CellPx - 10f,
        Config.CellPx - 10f
      )
    }

  private def drawEnemies(w: World): Unit =
    val size = 16f
    w.enemies.foreach { e =>
      val (ex, ey) = e.pos
      if e.slowed then
        shapes.setColor(Config.SlowTint)
        shapes.rect(ex - size / 2 - 3f, ey - size / 2 - 3f, size + 6f, size + 6f)
      if e.burning then
        shapes.setColor(Config.BurnTint)
        shapes.rect(ex - size / 2 - 1.5f, ey - size / 2 - 1.5f, size + 3f, size + 3f)
      shapes.setColor(Config.EnemyColor)
      shapes.rect(ex - size / 2, ey - size / 2, size, size)

      // hp bar floating above the square
      val barW = 20f
      val frac = math.max(0f, math.min(1f, e.hp / e.maxHp))
      shapes.setColor(Config.HpBarBack)
      shapes.rect(ex - barW / 2, ey + size / 2 + 3f, barW, 3f)
      shapes.setColor(Config.HpBarFill)
      shapes.rect(ex - barW / 2, ey + size / 2 + 3f, barW * frac, 3f)
    }

  private def shotColor(kind: DamageKind): Color = kind match
    case DamageKind.Kinetic   => Config.ShotColor
    case DamageKind.Fire      => Config.ShotFireColor
    case DamageKind.Slow      => Config.ShotFrostColor
    case DamageKind.Splash    => Config.ShotSplashColor
    case DamageKind.Explosion => Config.ShotBoomColor

  private def drawShots(w: World): Unit =
    w.shots.foreach { p =>
      shapes.setColor(shotColor(p.kind))
      shapes.rect(p.x - 3f, p.y - 3f, 6f, 6f)
    }

  private def drawHover(w: World, hover: Option[GridPos], selected: TowerType): Unit =
    hover.foreach { cell =>
      shapes.setColor(if Sim.canBuild(w, cell, selected) then Config.HoverOk else Config.HoverBad)
      shapes.rect(cell.x * Config.CellPx, cell.y * Config.CellPx, Config.CellPx, Config.CellPx)
    }

  /** Range ring on the hovered cell, plus every tower already placed. */
  private def drawRanges(w: World, hover: Option[GridPos], selected: TowerType): Unit =
    w.towers.foreach { t =>
      shapes.setColor(t.tpe.color)
      shapes.circle(t.cell.centerX, t.cell.centerY, t.tpe.range)
    }
    hover.filter(Grid.isBuildable).foreach { c =>
      shapes.setColor(selected.color)
      shapes.circle(c.centerX, c.centerY, selected.range)
    }

  private def drawHud(w: World, fast: Boolean, selected: TowerType): Unit =
    val x = Config.BoardPx + 20f
    var y = Config.WorldH - 30f
    def line(s: String, scale: Float = 1.2f, c: Color = Config.TextColor): Unit =
      text(s, x, y, scale, c)
      y -= 30f

    line(s"GOLD   ${w.gold}", 1.5f, Config.ShotColor)
    line(s"LIVES  ${w.lives}", 1.5f, Config.EnemyColor)
    line(s"WAVE   ${w.wave}", 1.5f, Config.TextColor)
    y -= 12f

    if w.inBreak then
      line(f"next wave in ${w.breakTimer}%.1fs", 1.1f, Config.DimText)
    else
      line(s"left to spawn: ${w.spawnQueue}", 1.1f, Config.DimText)
      line(s"on the field: ${w.enemies.size}", 1.1f, Config.DimText)

    y -= 20f
    line("build [1..5]", 1.0f, Config.DimText)
    TowerType.values.foreach { t =>
      val isSel  = t == selected
      val afford = w.gold >= t.cost
      val c      = if isSel then t.color else if afford then Config.TextColor else Config.DimText
      line(f"${TowerType.values.indexOf(t) + 1} ${t.name}%-8s ${t.cost}%3dg", 1.1f, c)
    }

    y = 70f
    line(if fast then s"F: fast-forward ${Config.FastForward}x ON" else "F: fast-forward", 1.1f,
      if fast then Config.ShotColor else Config.DimText)
    line("ESC: abandon run", 1.1f, Config.DimText)
