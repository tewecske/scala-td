package td

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.{Color, GL20, Mesh, OrthographicCamera, Pixmap, VertexAttribute}
import com.badlogic.gdx.graphics.g2d.{BitmapFont, GlyphLayout, SpriteBatch}
import com.badlogic.gdx.graphics.glutils.{FrameBuffer, ShaderProgram, ShapeRenderer}
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport

/** Everything the player sees. Squares via ShapeRenderer, text via libGDX's
  * built-in font — no asset files anywhere. A bright-pass → blur → composite
  * bloom pipeline makes the neon palette glow.
  */
class Render:
  private val camera   = new OrthographicCamera()
  private val viewport = new FitViewport(Config.WorldW, Config.WorldH, camera)
  private val shapes   = new ShapeRenderer()
  private val batch    = new SpriteBatch()
  private val font     = new BitmapFont()
  private val layout   = new GlyphLayout()
  private val scratch  = new Vector2()

  // -- bloom tuning -----------------------------------------------------------

  private val BloomThreshold = 0.4f
  private val BloomStrength  = 0.9f

  private var sceneFbo: FrameBuffer     = null
  private var bloomA: FrameBuffer       = null
  private var bloomB: FrameBuffer       = null
  private var brightness: ShaderProgram = null
  private var blur: ShaderProgram       = null
  private var combine: ShaderProgram    = null
  private var quad: Mesh                = null
  private var fboW                      = -1
  private var fboH                      = -1
  private var bloomOk                   = false

  font.setUseIntegerPositions(false)

  // -- floating popups/flashes -------------------------------------------------

  private val PopupLife = 0.9f
  private val FlashLife = 1.6f

  private case class Floating(x: Float, y: Float, label: String, color: Color, life: Float, maxLife: Float, big: Boolean)
  private val floating = scala.collection.mutable.ArrayBuffer[Floating]()

  /** Register a landed hit as a floating damage/status label. */
  def feedHits(hits: Vector[HitFeedback]): Unit =
    hits.foreach { h =>
      val (label, color) =
        if h.resistFrac >= 0.99f then (Messages.t("popup.immune"), Config.EnemyColor)
        else if h.resistFrac > 0f then (Messages.t("popup.resist"), Config.EnemyColor)
        else (math.round(h.dealt).toString, Config.TextColor)
      floating += Floating(h.x, h.y + 14f, label, color, PopupLife, PopupLife, big = false)
    }

  /** Register a first-meet / name-reveal flash. Unknown names show "???" until
    * the journal knows them (a killing blow in any run).
    */
  def feedNames(names: Vector[NameEvent], known: Set[String]): Unit =
    names.foreach { n =>
      val label =
        if n.reveal then n.enemy
        else if known.contains(n.enemy) then n.enemy
        else Messages.t("popup.unknown")
      floating += Floating(n.x, n.y + 26f, label, Config.TextColor, FlashLife, FlashLife, big = true)
    }

  /** Ages the floating labels once per frame (call even when not drawing them). */
  def updateFx(dt: Float): Unit =
    if floating.nonEmpty then
      val aged = floating.map(f => f.copy(life = f.life - dt)).filter(_.life > 0f)
      floating.clear()
      floating ++= aged

  def clearFx(): Unit = floating.clear()

  private def drawFloating(): Unit =
    floating.foreach { f =>
      val t     = f.life / f.maxLife
      val rise  = (1f - t) * 12f
      val scale = if f.big then 1.4f else 1.0f
      val c     = new Color(f.color)
      c.a       = math.min(1f, t * 2f)
      centered(f.label, f.x, f.y + rise, scale, c)
    }

  def resize(w: Int, h: Int): Unit = viewport.update(w, h, true)

  /** Screen pixel -> world unit, so clicks land where they look like they land. */
  def toWorld(screenX: Int, screenY: Int): (Float, Float) =
    val v = viewport.unproject(scratch.set(screenX.toFloat, screenY.toFloat))
    (v.x, v.y)

  def dispose(): Unit =
    shapes.dispose()
    batch.dispose()
    font.dispose()
    if sceneFbo != null then sceneFbo.dispose()
    if bloomA != null then bloomA.dispose()
    if bloomB != null then bloomB.dispose()
    if brightness != null then brightness.dispose()
    if blur != null then blur.dispose()
    if combine != null then combine.dispose()
    if quad != null then quad.dispose()

  // -- frame scaffolding ------------------------------------------------------

  private def beginScene(): Unit =
    ensureBloom()
    if bloomOk then sceneFbo.begin()
    viewport.apply()
    ScreenUtils.clear(Config.Background)
    Gdx.gl.glEnable(GL20.GL_BLEND)
    shapes.setProjectionMatrix(camera.combined)
    batch.setProjectionMatrix(camera.combined)

  private def present(): Unit =
    if bloomOk then
      sceneFbo.end()
      renderBloom()

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

  /** Soft stepped halo, drawn under a bright core so bloom can lift it. */
  private def glow(x: Float, y: Float, r: Float, c: Color): Unit =
    shapes.setColor(c.r, c.g, c.b, 0.14f)
    shapes.circle(x, y, r)
    shapes.setColor(c.r, c.g, c.b, 0.22f)
    shapes.circle(x, y, r * 0.65f)
    shapes.setColor(c.r, c.g, c.b, 0.34f)
    shapes.circle(x, y, r * 0.35f)

  private def kindName(kind: DamageKind): String = Messages.t(s"kind.${kind.toString}")

  private def entryParts(e: JournalEntry): (String, String) =
    val v = e.value.getOrElse("")
    e.traitKey match
      case "name" => (e.enemy, "")
      case "aura" => (e.enemy, v)
      case "heavy" => (e.enemy, "")
      case "route" =>
        val detail =
          if e.tier == Precision.Studied then Messages.t("recap.routeStudied", e.value.getOrElse(""))
          else Messages.t("recap.routeVague")
        (e.enemy, detail)
      case k if k.startsWith("resist.") =>
        val kind   = DamageKind.valueOf(k.stripPrefix("resist."))
        val detail =
          if e.tier == Precision.Studied then Messages.t("recap.resistExact", kindName(kind), v)
          else Messages.t("recap.resistVague", kindName(kind), v)
        (e.enemy, detail)
      case k => (e.enemy, s"$k $v")

  // -- screens ---------------------------------------------------------------

  def drawMenu(): Unit =
    beginScene()
    shapes.begin(ShapeType.Filled)
    button(Ui.Play)
    shapes.end()

    val towers = Config.game.towers.size
    batch.begin()
    centered("TOWER DEFENSE", Config.WorldW / 2, 440f, 3f)
    centered(s"${Config.GridSize} x ${Config.GridSize} · hold the line", Config.WorldW / 2, 390f, 1.2f, Config.DimText)
    buttonLabel(Ui.Play, "PLAY")
    centered(s"1-$towers to pick a tower · click green to build · F to fast-forward · R to reload config · ESC to pause",
      Config.WorldW / 2, 120f, 1f, Config.DimText)
    batch.end()
    present()

  def drawRecap(
      won: Boolean,
      level: Int,
      wave: Int,
      lives: Int,
      gold: Int,
      newEntries: Vector[JournalEntry],
      knownEntries: Vector[JournalEntry],
      death: Option[DeathRecap]
  ): Unit =
    beginScene()
    shapes.begin(ShapeType.Filled)
    button(Ui.Retry)
    button(Ui.Menu)
    shapes.end()

    batch.begin()
    val title      = if won then "VICTORY" else "GAME OVER"
    val titleColor = if won then Config.BuffTint else Config.EnemyColor
    centered(title, Config.WorldW / 2, 600f, 3f, titleColor)

    val stats =
      if won then s"all ${level + 1} levels cleared - $lives lives remaining - $gold gold"
      else s"you reached level ${level + 1} - wave $wave"
    centered(stats, Config.WorldW / 2, 548f, 1.3f, Config.DimText)

    death.foreach { d =>
      centered(Messages.t("recap.deathResisted", d.leakPct, kindName(d.resistedKind)),
        Config.WorldW / 2, 505f, 1.2f, Config.EnemyColor)
      if d.missingCounter then
        centered(Messages.t("recap.deathMissing", kindName(d.counterKind)),
          Config.WorldW / 2, 478f, 1.2f, Config.EnemyColor)
    }

    centered(Messages.t("recap.discoveries"), Config.WorldW / 2, 432f, 1.6f)

    var y = 396f
    def line(s: String, c: Color): Unit =
      if y > 240f then
        text(s, Config.WorldW / 2 - 190f, y, 1.1f, c)
        y -= 22f

    newEntries.foreach { e =>
      val (name, detail) = entryParts(e)
      line(s"${Messages.t("recap.new")}  $name${if detail.isEmpty then "" else s" - $detail"}", Config.TextColor)
    }
    knownEntries.foreach { e =>
      val (name, detail) = entryParts(e)
      line(s"${Messages.t("recap.known")}  $name${if detail.isEmpty then "" else s" - $detail"}", Config.DimText)
    }
    if newEntries.isEmpty && knownEntries.isEmpty then
      line(Messages.t("recap.none"), Config.DimText)

    buttonLabel(Ui.Retry, if won then "PLAY AGAIN" else "RETRY")
    buttonLabel(Ui.Menu, "MENU")
    batch.end()
    present()

  def drawGame(w: World, hover: Option[GridPos], fast: Boolean, selected: TowerSpec, notice: Option[String] = None): Unit =
    beginScene()
    drawScene(w, hover, fast, selected, notice)
    present()

  /** The between-level draft: the offers on the table plus the reroll button and
    * the player's gold. No sim is stepped while this is on screen.
    */
  def drawDraft(w: World): Unit =
    beginScene()
    val d = w.draft.get
    val unlocked = Meta.effects(w.run.talents).rerollUnlocked
    val canReroll = unlocked && w.gold >= d.rerollCost

    shapes.begin(ShapeType.Filled)
    d.offers.zipWithIndex.foreach { case (_, i) => button(Ui.DraftOffer(i)) }
    button(Ui.DraftReroll)
    shapes.end()

    batch.begin()
    centered(Messages.t("draft.title"), Config.WorldW / 2, 560f, 3f)
    d.offers.zipWithIndex.foreach { case (offer, i) =>
      val (kind, name, extra) = offer match
        case DraftOffer.Relic(id) =>
          val r = Config.relic(id)
          (Messages.t("draft.relic"), r.name, Messages.t(s"draft.rarity.${r.rarity}"))
        case DraftOffer.Augment(id) =>
          val a = Config.augment(id)
          (Messages.t("draft.augment"), a.name, "")
      val r = Ui.DraftOffer(i)
      text(s"$kind - $name  $extra", r.x + 16f, r.centerY + 8f, 1.2f)
    }
    val rerollLabel =
      if !unlocked then Messages.t("draft.rerollLocked")
      else Messages.t("draft.reroll", d.rerollCost)
    centered(rerollLabel, Ui.DraftReroll.centerX, Ui.DraftReroll.centerY + 8f, 1.4f,
      if canReroll then Config.TextColor else Config.DimText)
    centered(s"GOLD ${w.gold}", Config.WorldW / 2, 90f, 1.4f, Config.ShotColor)
    batch.end()
    present()

  /** Frozen game view under a dim overlay plus the pause panel and its buttons. */
  def drawPaused(w: World, fast: Boolean, selected: TowerSpec): Unit =
    beginScene()
    drawScene(w, None, fast, selected, None)

    val panel = Rect(Config.WorldW / 2 - 170f, 130f, 340f, 350f)

    shapes.begin(ShapeType.Filled)
    shapes.setColor(0f, 0f, 0f, 0.55f)
    shapes.rect(0f, 0f, Config.WorldW, Config.WorldH)
    shapes.setColor(Config.PanelColor)
    shapes.rect(panel.x, panel.y, panel.w, panel.h)
    button(Ui.Resume)
    button(Ui.Exit)
    shapes.end()

    batch.begin()
    centered("PAUSED", Config.WorldW / 2, 440f, 3f)
    buttonLabel(Ui.Resume, "RESUME")
    buttonLabel(Ui.Exit, "EXIT")
    batch.end()

    present()

  /** The in-game scene: board, actors, shots, overlays and the HUD. */
  private def drawScene(w: World, hover: Option[GridPos], fast: Boolean, selected: TowerSpec, notice: Option[String]): Unit =
    shapes.begin(ShapeType.Filled)
    drawBoard(w.level)
    drawTowers(w)
    drawEnemies(w)
    drawShots(w)
    drawHover(w, hover, selected)
    shapes.setColor(Config.PanelColor)
    shapes.rect(Config.BoardPx, 0f, Config.PanelPx, Config.WorldH)
    shapes.end()

    shapes.begin(ShapeType.Line)
    drawRanges(w, hover, selected)
    drawAuras(w)
    shapes.end()

    batch.begin()
    drawHud(w, fast, selected)
    drawFloating()
    notice.foreach(n => centered(n, Config.BoardPx + Config.PanelPx / 2, 30f, 1.1f, Config.ShotColor))
    batch.end()

  // -- board pieces ----------------------------------------------------------

  /** Cells are drawn inset by one unit, which leaves the dark background
    * showing through as grid lines for free.
    */
  private def drawBoard(level: Int): Unit =
    val path = Grid.pathCells(level)
    for
      gx <- 0 until Config.GridSize
      gy <- 0 until Config.GridSize
    do
      val cell = GridPos(gx, gy)
      shapes.setColor(if path.contains(cell) then Config.PathColor else Config.BuildColor)
      shapes.rect(gx * Config.CellPx + 1f, gy * Config.CellPx + 1f, Config.CellPx - 2f, Config.CellPx - 2f)

  private def drawTowers(w: World): Unit =
    w.towers.foreach { t =>
      glow(t.cell.centerX, t.cell.centerY, Config.CellPx * 0.65f, t.spec.color)
      shapes.setColor(t.spec.color)
      shapes.rect(
        t.cell.x * Config.CellPx + 5f,
        t.cell.y * Config.CellPx + 5f,
        Config.CellPx - 10f,
        Config.CellPx - 10f
      )
      // While an augment is held, mark every socketable tower (build-hover color).
      if w.pendingAugment.isDefined && t.augment.isEmpty then
        shapes.setColor(Config.HoverOk)
        shapes.rect(t.cell.x * Config.CellPx, t.cell.y * Config.CellPx, Config.CellPx, Config.CellPx)
    }

  private def drawEnemies(w: World): Unit =
    w.enemies.foreach { e =>
      val size = e.spec.size
      val (ex, ey) = e.pos
      if e.slowed then
        shapes.setColor(Config.SlowTint)
        shapes.rect(ex - size / 2 - 3f, ey - size / 2 - 3f, size + 6f, size + 6f)
      if e.burning then
        shapes.setColor(Config.BurnTint)
        shapes.rect(ex - size / 2 - 1.5f, ey - size / 2 - 1.5f, size + 3f, size + 3f)
      shapes.setColor(e.spec.color)
      shapes.rect(ex - size / 2, ey - size / 2, size, size)
      if e.buffed then
        shapes.setColor(Config.BuffTint)
        shapes.rect(ex - size / 2 - 3f, ey - size / 2 - 3f, 5f, 5f)

      // hp bar floating above the square
      val barW = 20f
      val frac = math.max(0f, math.min(1f, e.hp / e.maxHp))
      shapes.setColor(Config.HpBarBack)
      shapes.rect(ex - barW / 2, ey + size / 2 + 3f, barW, 3f)
      shapes.setColor(Config.HpBarFill)
      shapes.rect(ex - barW / 2, ey + size / 2 + 3f, barW * frac, 3f)
    }

  /** Ring around each aura carrier so its buff radius is legible. */
  private def drawAuras(w: World): Unit =
    w.enemies.foreach { e =>
      e.spec.aura.foreach { a =>
        val (ex, ey) = e.pos
        shapes.setColor(e.spec.color)
        shapes.circle(ex, ey, a.radius)
      }
    }

  private def shotColor(kind: DamageKind): Color = kind match
    case DamageKind.Kinetic   => Config.ShotColor
    case DamageKind.Fire      => Config.ShotFireColor
    case DamageKind.Slow      => Config.ShotFrostColor
    case DamageKind.Splash    => Config.ShotSplashColor
    case DamageKind.Explosion => Config.ShotBoomColor

  private def drawShots(w: World): Unit =
    w.shots.foreach { p =>
      val c = shotColor(p.kind)
      glow(p.x, p.y, 10f, c)
      shapes.setColor(c)
      shapes.rect(p.x - 3f, p.y - 3f, 6f, 6f)
    }

  private def drawHover(w: World, hover: Option[GridPos], selected: TowerSpec): Unit =
    hover.foreach { cell =>
      shapes.setColor(if Sim.canBuild(w, cell, selected.name) then Config.HoverOk else Config.HoverBad)
      shapes.rect(cell.x * Config.CellPx, cell.y * Config.CellPx, Config.CellPx, Config.CellPx)
    }

  /** Range ring on the hovered cell, plus every tower already placed. */
  private def drawRanges(w: World, hover: Option[GridPos], selected: TowerSpec): Unit =
    w.towers.foreach { t =>
      shapes.setColor(t.spec.color)
      shapes.circle(t.cell.centerX, t.cell.centerY, t.spec.range)
    }
    hover.filter(c => Grid.isBuildable(c, w.level)).foreach { c =>
      shapes.setColor(selected.color)
      shapes.circle(c.centerX, c.centerY, selected.range)
    }

  private def drawHud(w: World, fast: Boolean, selected: TowerSpec): Unit =
    val x = Config.BoardPx + 20f
    var y = Config.WorldH - 30f
    def line(s: String, scale: Float = 1.2f, c: Color = Config.TextColor): Unit =
      text(s, x, y, scale, c)
      y -= 30f

    line(s"GOLD   ${w.gold}", 1.5f, Config.ShotColor)
    line(s"LIVES  ${w.lives}", 1.5f, Config.EnemyColor)
    line(s"LEVEL  ${w.level + 1} / ${Config.levelCount}  ${Config.levelAt(w.level).name}", 1.3f, Config.TextColor)
    line(s"WAVE   ${w.wave} / ${Waves.waveCount(w.level)}", 1.5f, Config.TextColor)
    w.run.twists.foreach { id =>
      Config.twists.find(_.id == id).foreach { tw =>
        line(s"${Messages.t("briefing.twist")} ${tw.name} - ${Messages.t(s"twist.${tw.id}")}", 1.0f, Config.ShotColor)
      }
    }
    if w.run.twists.nonEmpty then y -= 8f
    w.pendingAugment.foreach { id =>
      line(s"${Config.augment(id).name} - ${Messages.t("draft.socketHint")}", 1.1f, Config.ShotColor)
    }
    y -= 12f

    if w.inBreak then
      line(f"next wave in ${w.breakTimer}%.1fs", 1.1f, Config.DimText)
    else
      line(s"left to spawn: ${w.spawnQueue.size}", 1.1f, Config.DimText)
      line(s"on the field: ${w.enemies.size}", 1.1f, Config.DimText)

    val waveNum = if w.inBreak then math.min(w.wave + 1, Waves.waveCount(w.level)) else w.wave
    val kinds = Waves.spec(w.level, waveNum).composition.map(_._1).mkString(", ")
    line(s"types: $kinds", 1.0f, Config.DimText)

    y -= 20f
    val towers = Config.game.towers
    line(s"build [1..${towers.size}]", 1.0f, Config.DimText)
    towers.zipWithIndex.foreach { case (t, idx) =>
      val isSel  = t.name == selected.name
      val afford = w.gold >= t.cost
      val c      = if isSel then t.color else if afford then Config.TextColor else Config.DimText
      line(f"${idx + 1} ${t.name}%-8s ${t.cost}%3dg", 1.1f, c)
    }

    y = 70f
    line(if fast then s"F: fast-forward ${Config.FastForward}x ON" else "F: fast-forward", 1.1f,
      if fast then Config.ShotColor else Config.DimText)
    line("R: reload config", 1.1f, Config.DimText)
    line("ESC / P: pause", 1.1f, Config.DimText)

  // -- bloom ------------------------------------------------------------------

  private def ensureBloom(): Unit =
    val w = Gdx.graphics.getWidth
    val h = Gdx.graphics.getHeight
    if sceneFbo == null || w != fboW || h != fboH then
      if sceneFbo != null then sceneFbo.dispose()
      if bloomA != null then bloomA.dispose()
      if bloomB != null then bloomB.dispose()
      fboW = w
      fboH = h
      sceneFbo = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false)
      bloomA   = new FrameBuffer(Pixmap.Format.RGBA8888, math.max(1, w / 2), math.max(1, h / 2), false)
      bloomB   = new FrameBuffer(Pixmap.Format.RGBA8888, math.max(1, w / 2), math.max(1, h / 2), false)

    if quad == null then
      quad = new Mesh(true, 4, 6, VertexAttribute.Position(), VertexAttribute.TexCoords(0))
      quad.setVertices(Array[Float](
        -1f, -1f, 0f, 0f, 0f,
         1f, -1f, 0f, 1f, 0f,
         1f,  1f, 0f, 1f, 1f,
        -1f,  1f, 0f, 0f, 1f
      ))
      quad.setIndices(Array[Short](0, 1, 2, 2, 3, 0))

    if brightness == null then
      brightness = new ShaderProgram(VertexShader, BrightnessFragment)
      blur       = new ShaderProgram(VertexShader, BlurFragment)
      combine    = new ShaderProgram(VertexShader, CombineFragment)
      if !brightness.isCompiled then Gdx.app.error("Bloom", brightness.getLog)
      if !blur.isCompiled then Gdx.app.error("Bloom", blur.getLog)
      if !combine.isCompiled then Gdx.app.error("Bloom", combine.getLog)

    bloomOk = sceneFbo != null && quad != null &&
      brightness != null && brightness.isCompiled &&
      blur != null && blur.isCompiled &&
      combine != null && combine.isCompiled

  private def renderBloom(): Unit =
    Gdx.gl.glDisable(GL20.GL_BLEND)

    // 1. keep only the bright parts (downsampled)
    bloomA.begin()
    Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    brightness.bind()
    brightness.setUniformi("u_texture", 0)
    brightness.setUniformf("u_threshold", BloomThreshold)
    sceneFbo.getColorBufferTexture.bind(0)
    quad.render(brightness, GL20.GL_TRIANGLES)
    bloomA.end()

    // 2. two-pass gaussian blur, ping-ponging between the two small buffers
    val texW = 1f / bloomA.getWidth
    val texH = 1f / bloomA.getHeight
    blur.bind()
    blur.setUniformi("u_texture", 0)
    blur.setUniformf("u_texelSize", texW, texH)

    blur.setUniformf("u_direction", 1f, 0f)
    bloomB.begin()
    Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    bloomA.getColorBufferTexture.bind(0)
    quad.render(blur, GL20.GL_TRIANGLES)
    bloomB.end()

    blur.setUniformf("u_direction", 0f, 1f)
    bloomA.begin()
    Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    bloomB.getColorBufferTexture.bind(0)
    quad.render(blur, GL20.GL_TRIANGLES)
    bloomA.end()

    // 3. add the blurred glow back over the original scene
    Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    combine.bind()
    combine.setUniformi("u_scene", 0)
    combine.setUniformi("u_bloom", 1)
    combine.setUniformf("u_strength", BloomStrength)
    sceneFbo.getColorBufferTexture.bind(0)
    bloomA.getColorBufferTexture.bind(1)
    Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
    quad.render(combine, GL20.GL_TRIANGLES)

    Gdx.gl.glEnable(GL20.GL_BLEND)

  // -- shaders ----------------------------------------------------------------

  private val VertexShader: String =
    """attribute vec4 a_position;
      |attribute vec2 a_texCoord0;
      |varying vec2 v_texCoords;
      |void main() {
      |  v_texCoords = a_texCoord0;
      |  gl_Position = a_position;
      |}""".stripMargin

  private val BrightnessFragment: String =
    """varying vec2 v_texCoords;
      |uniform sampler2D u_texture;
      |uniform float u_threshold;
      |void main() {
      |  vec4 c = texture2D(u_texture, v_texCoords);
      |  float b = dot(c.rgb, vec3(0.299, 0.587, 0.114));
      |  float f = smoothstep(u_threshold, u_threshold + 0.4, b);
      |  gl_FragColor = vec4(c.rgb * f, 1.0);
      |}""".stripMargin

  private val BlurFragment: String =
    """varying vec2 v_texCoords;
      |uniform sampler2D u_texture;
      |uniform vec2 u_texelSize;
      |uniform vec2 u_direction;
      |void main() {
      |  vec2 uv = v_texCoords;
      |  vec4 sum = texture2D(u_texture, uv) * 0.2270270270;
      |  sum += texture2D(u_texture, uv + u_direction * u_texelSize * 1.3846153846) * 0.3162162162;
      |  sum += texture2D(u_texture, uv - u_direction * u_texelSize * 1.3846153846) * 0.3162162162;
      |  sum += texture2D(u_texture, uv + u_direction * u_texelSize * 3.2307692308) * 0.0702702703;
      |  sum += texture2D(u_texture, uv - u_direction * u_texelSize * 3.2307692308) * 0.0702702703;
      |  gl_FragColor = sum;
      |}""".stripMargin

  private val CombineFragment: String =
    """varying vec2 v_texCoords;
      |uniform sampler2D u_scene;
      |uniform sampler2D u_bloom;
      |uniform float u_strength;
      |void main() {
      |  vec4 s = texture2D(u_scene, v_texCoords);
      |  vec4 b = texture2D(u_bloom, v_texCoords);
      |  gl_FragColor = vec4(s.rgb + b.rgb * u_strength, 1.0);
      |}""".stripMargin
