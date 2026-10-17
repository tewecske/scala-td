package td

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.{Color, GL20, Mesh, OrthographicCamera, Pixmap, VertexAttribute}
import com.badlogic.gdx.graphics.g2d.{GlyphLayout, SpriteBatch}
import com.badlogic.gdx.graphics.glutils.{FrameBuffer, ShaderProgram, ShapeRenderer}
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.{Matrix4, Vector2}
import com.badlogic.gdx.utils.{Align => GdxAlign, ScreenUtils}
import com.badlogic.gdx.utils.viewport.ExtendViewport
import scala.collection.mutable.ArrayBuffer

/** Everything the player sees. Shapes via ShapeRenderer in UI units (see
  * [[Layout]]); text via TrueType fonts rasterized at the window's pixel size
  * (see [[Fonts]]) and drawn in screen pixels so it stays sharp. The board is
  * drawn in simulation coordinates through a transform that gives every map the
  * same tile size. A bright-pass -> blur -> composite bloom makes the neon glow.
  *
  * Text calls are queued while shapes are drawn and land on top of them when the
  * frame is presented, or earlier at a [[flush]] (used before overlays).
  */
class Render:
  private val camera   = new OrthographicCamera()
  private val viewport = new ExtendViewport(Layout.RefW, Layout.RefH, camera)
  private val textCam  = new OrthographicCamera()
  private val shapes   = new ShapeRenderer()
  private val batch    = new SpriteBatch()
  private val fonts    = new Fonts
  private val glyphs   = new GlyphLayout()
  private val scratch  = new Vector2()
  private val rng      = new scala.util.Random()
  private val identity = new Matrix4()

  /** Screen pixels per UI unit. */
  private var ppu: Float = 1f

  /** The mouse in UI units, for hover highlights. */
  var mouse: (Float, Float) = (-1f, -1f)

  /** Seconds since start, for menu animation. */
  private var time: Float = 0f

  private val texts = ArrayBuffer[() => Unit]()

  // -- camera shake (life loss) ----------------------------------------------
  private var shakeTime: Float = 0f
  private var shakeMag: Float  = 0f

  // -- hit sparks (simulation coordinates) -------------------------------------
  private case class Spark(x: Float, y: Float, vx: Float, vy: Float, life: Float, maxLife: Float)
  private val sparks = ArrayBuffer[Spark]()

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

  // -- floating popups/flashes (simulation coordinates) --------------------------

  private val PopupLife = 0.9f

  /** Damage numbers of hits the target partly resisted. */
  private val ResistedColor = new Color(0.60f, 0.72f, 0.92f, 1f)
  private val FlashLife = 1.6f

  private case class Floating(x: Float, y: Float, label: String, color: Color, life: Float, maxLife: Float, big: Boolean)
  private val floating = ArrayBuffer[Floating]()

  /** Register a landed hit as a floating damage/status label + a spark burst. */
  def feedHits(hits: Vector[HitFeedback]): Unit =
    hits.foreach { h =>
      // resisted hits show their (reduced) damage in a cool color; immune ones say so
      val (label, color) =
        if h.resistFrac >= 0.99f then (Messages.t("popup.immune"), Config.EnemyColor)
        else if h.resistFrac > 0f then (math.round(h.dealt).toString, ResistedColor)
        else (math.round(h.dealt).toString, Config.TextColor)
      floating += Floating(h.x, h.y + 14f, label, color, PopupLife, PopupLife, big = false)
      for _ <- 0 until Config.SparkCount do
        val ang = rng.nextFloat() * math.Pi * 2f
        val spd = Config.SparkSpeed * (0.4f + rng.nextFloat() * 0.6f)
        sparks += Spark(h.x, h.y, math.cos(ang).toFloat * spd, math.sin(ang).toFloat * spd,
          Config.SparkLife, Config.SparkLife)
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

  /** Ages the floating labels + sparks and decays the shake once per frame. */
  def updateFx(dt: Float): Unit =
    time += dt
    if floating.nonEmpty then
      val aged = floating.map(f => f.copy(life = f.life - dt)).filter(_.life > 0f)
      floating.clear()
      floating ++= aged
    if sparks.nonEmpty then
      val aged = sparks.map(s => Spark(s.x + s.vx * dt, s.y + s.vy * dt, s.vx, s.vy, s.life - dt, s.maxLife))
        .filter(_.life > 0f)
      sparks.clear()
      sparks ++= aged
    if shakeTime > 0f then shakeTime = math.max(0f, shakeTime - dt)

  def clearFx(): Unit =
    floating.clear()
    sparks.clear()
    shakeTime = 0f

  /** Triggers a camera shake (called when a life is lost). */
  def shake(): Unit =
    shakeTime = Config.ShakeDuration
    shakeMag  = Config.ShakeMagnitude

  def resize(w: Int, h: Int): Unit =
    viewport.update(w, h, true)
    Layout.W = viewport.getWorldWidth
    Layout.H = viewport.getWorldHeight
    ppu = viewport.getScreenWidth / Layout.W
    fonts.rebuild(ppu)
    textCam.setToOrtho(false, viewport.getScreenWidth.toFloat, viewport.getScreenHeight.toFloat)

  /** Screen pixel -> UI unit, so clicks land where they look like they land. */
  def toUi(screenX: Int, screenY: Int): (Float, Float) =
    val v = viewport.unproject(scratch.set(screenX.toFloat, screenY.toFloat))
    (v.x, v.y)

  def dispose(): Unit =
    shapes.dispose()
    batch.dispose()
    fonts.dispose()
    Seq(sceneFbo, bloomA, bloomB).filter(_ != null).foreach(_.dispose())
    Seq(brightness, blur, combine).filter(_ != null).foreach(_.dispose())
    if quad != null then quad.dispose()

  // -- palette --------------------------------------------------------------------

  private def mix(a: Color, b: Color, t: Float): Color =
    new Color(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, a.a + (b.a - a.a) * t)
  private def alpha(c: Color, a: Float): Color = new Color(c.r, c.g, c.b, a)

  private def text0: Color   = Config.TextColor
  /** Secondary text: the text color pulled towards the background, readable on any panel. */
  private def muted: Color   = mix(Config.TextColor, Config.PanelColor, 0.42f)
  private def faint: Color   = mix(Config.TextColor, Config.PanelColor, 0.62f)
  private def accent: Color  = Config.BuffTint
  private def gold: Color    = Config.ShotColor
  private def danger: Color  = Config.EnemyColor
  private def surface: Color = mix(Config.PanelColor, Config.ButtonFill, 0.35f)

  /** Ambient-only global illumination: scale a surface color by the ambient
    * light tint * intensity. White at intensity 1 is a no-op.
    */
  private def lit(c: Color): Color =
    val a = Config.LightingAmbient
    val k = Config.LightingIntensity
    new Color(c.r * a.r * k, c.g * a.g * k, c.b * a.b * k, c.a)

  // -- frame scaffolding ------------------------------------------------------

  private def beginScene(): Unit =
    ensureBloom()
    if bloomOk then sceneFbo.begin()
    val (sx, sy) =
      if shakeTime > 0f then
        val k = shakeMag * (shakeTime / math.max(1e-3f, Config.ShakeDuration)) * 2f
        ((rng.nextFloat() * 2f - 1f) * k, (rng.nextFloat() * 2f - 1f) * k)
      else (0f, 0f)
    camera.position.set(Layout.W / 2f + sx, Layout.H / 2f + sy, 0f)
    viewport.apply()
    textCam.position.set(textCam.viewportWidth / 2f + sx * ppu, textCam.viewportHeight / 2f + sy * ppu, 0f)
    textCam.update()
    ScreenUtils.clear(Config.Background)
    Gdx.gl.glEnable(GL20.GL_BLEND)
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    shapes.setProjectionMatrix(camera.combined)
    shapes.setTransformMatrix(identity)
    batch.setProjectionMatrix(textCam.combined)
    texts.clear()
    shapes.begin(ShapeType.Filled)

  /** Draws the queued text now, so later shapes go on top of it. */
  private def flush(): Unit =
    shapes.end()
    if texts.nonEmpty then
      batch.begin()
      texts.foreach(_())
      batch.end()
      texts.clear()
    // SpriteBatch.end() switches blending off; translucent shapes after this need it.
    Gdx.gl.glEnable(GL20.GL_BLEND)
    shapes.begin(ShapeType.Filled)

  private def present(): Unit =
    flush()
    shapes.end()
    if bloomOk then
      sceneFbo.end()
      renderBloom()

  // -- primitives -------------------------------------------------------------------

  private def fill(r: Rect, c: Color): Unit =
    shapes.setColor(c)
    shapes.rect(r.x, r.y, r.w, r.h)

  /** A filled rectangle with rounded corners; corners are quarter discs so
    * nothing overlaps (translucent fills stay even).
    */
  private def round(r: Rect, rad: Float, c: Color): Unit =
    val k = math.min(rad, math.min(r.w, r.h) / 2)
    shapes.setColor(c)
    shapes.rect(r.x + k, r.y, r.w - 2 * k, r.h)
    shapes.rect(r.x, r.y + k, k, r.h - 2 * k)
    shapes.rect(r.right - k, r.y + k, k, r.h - 2 * k)
    shapes.arc(r.x + k, r.y + k, k, 180f, 90f, 10)
    shapes.arc(r.right - k, r.y + k, k, 270f, 90f, 10)
    shapes.arc(r.right - k, r.top - k, k, 0f, 90f, 10)
    shapes.arc(r.x + k, r.top - k, k, 90f, 90f, 10)

  /** A rounded outline `t` thick, drawn as a ring between two rounded fills. */
  private def outline(r: Rect, rad: Float, t: Float, c: Color, inside: Color): Unit =
    round(r, rad, c)
    round(r.inset(t), math.max(0f, rad - t), inside)

  private def hovered(r: Rect): Boolean = r.contains(mouse._1, mouse._2)

  private enum Look:
    case Normal, Primary, Disabled, Selected

  /** A button: rounded fill that brightens on hover, label centered in it. */
  private def button(r: Rect, label: String, look: Look = Look.Normal, style: TextStyle = TextStyle.Button): Unit =
    val hot  = hovered(r) && look != Look.Disabled
    val base = look match
      case Look.Primary  => mix(Config.PathColor, Config.Background, 0.25f)
      case Look.Disabled => mix(Config.ButtonFill, Config.PanelColor, 0.6f)
      case Look.Selected => mix(Config.ButtonFill, gold, 0.25f)
      case Look.Normal   => Config.ButtonFill
    val fillC = if hot then mix(base, Color.WHITE, 0.14f) else base
    if hot || look == Look.Primary || look == Look.Selected then
      val edge = look match
        case Look.Primary  => accent
        case Look.Selected => gold
        case _             => mix(base, Color.WHITE, 0.45f)
      outline(r, 12f, 2.5f, edge, fillC)
    else round(r, 12f, fillC)
    val tc = if look == Look.Disabled then faint else text0
    text(label, r.centerX, r.centerY, style, tc, Align.Center, r.w - 24f)

  /** A dark rounded panel with a thin lighter rim. */
  private def panel(r: Rect, c: Color = surface): Unit =
    outline(r, 16f, 1.5f, mix(c, Color.WHITE, 0.10f), c)

  /** Soft stepped halo, drawn under a bright core so bloom can lift it. */
  private def glow(x: Float, y: Float, r: Float, c: Color): Unit =
    shapes.setColor(c.r, c.g, c.b, 0.14f)
    shapes.circle(x, y, r, 24)
    shapes.setColor(c.r, c.g, c.b, 0.22f)
    shapes.circle(x, y, r * 0.65f, 20)
    shapes.setColor(c.r, c.g, c.b, 0.34f)
    shapes.circle(x, y, r * 0.35f, 16)

  // -- text -------------------------------------------------------------------------

  private enum Align:
    case Left, Center, Right

  private def gdxAlign(a: Align): Int = a match
    case Align.Left   => GdxAlign.left
    case Align.Center => GdxAlign.center
    case Align.Right  => GdxAlign.right

  /** Queues one line of text whose capitals are vertically centered on `y`.
    * `x` is the left edge, center or right edge depending on `align`; with
    * `maxW` the text is cut short with an ellipsis instead of overflowing.
    */
  private def text(s: String, x: Float, y: Float, style: TextStyle = TextStyle.Body, c: Color = Config.TextColor,
      align: Align = Align.Left, maxW: Float = 0f): Unit =
    val color = new Color(c)
    texts += { () =>
      val f      = fonts(style)
      val target = if maxW > 0f then maxW * ppu else 0f
      // Without a target width GlyphLayout would shift centered text itself; we place it below instead.
      val halign = if target > 0f then gdxAlign(align) else GdxAlign.left
      glyphs.setText(f, s, 0, s.length, color, target, halign, false, if maxW > 0f then "…" else null)
      val boxW = if target > 0f then target else glyphs.width
      val left = align match
        case Align.Left   => x * ppu
        case Align.Center => x * ppu - boxW / 2
        case Align.Right  => x * ppu - boxW
      f.draw(batch, glyphs, math.round(left).toFloat, math.round(y * ppu + f.getCapHeight / 2).toFloat)
    }

  /** Queues text wrapped to `w` UI units starting at `x`, top line centered on `y`
    * (lines aligned within the width by `align`); returns the height used.
    */
  private def wrapped(s: String, x: Float, y: Float, w: Float, style: TextStyle = TextStyle.Body,
      c: Color = Config.TextColor, align: Align = Align.Left): Float =
    val f      = fonts(style)
    val layout = new GlyphLayout(f, s, new Color(c), w * ppu, gdxAlign(align), true)
    texts += (() => f.draw(batch, layout, math.round(x * ppu).toFloat, math.round(y * ppu + f.getCapHeight / 2).toFloat))
    layout.height / ppu + (f.getLineHeight - f.getCapHeight) / ppu

  private def lineHeight(style: TextStyle): Float = fonts(style).getLineHeight / ppu

  private def heading(s: String, sub: String = "", c: Color = Config.TextColor): Unit =
    text(s, Layout.cx, Layout.titleY, TextStyle.Heading, c, Align.Center)
    if sub.nonEmpty then text(sub, Layout.cx, Layout.subtitleY, TextStyle.Label, muted, Align.Center)

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

  // -- backdrop ---------------------------------------------------------------------

  /** The menus' backdrop: a dim tiled field with a road. On the main menu
    * (`animated`) enemies march along it past a few towers. Purely decorative.
    */
  private def backdrop(animated: Boolean = false): Unit =
    val t     = 72f
    val cols  = (Layout.W / t).toInt + 2
    val rows  = (Layout.H / t).toInt + 2
    val ox    = (Layout.W - cols * t) / 2
    val oy    = (Layout.H - rows * t) / 2
    val road  = backdropRoad(cols, rows)
    val cells = road.toSet
    fill(Rect(0f, 0f, Layout.W, Layout.H), Config.GridLine)
    for gx <- 0 until cols; gy <- 0 until rows do
      val c = if cells.contains((gx, gy)) then mix(Config.PathColor, Config.Background, 0.4f) else Config.BuildColor
      fill(Rect(ox + gx * t + 2f, oy + gy * t + 2f, t - 4f, t - 4f), lit(c))
    if animated then backdropActors(road, cells, cols, rows, t, ox, oy)
    // darken so the UI on top reads clearly
    fill(Rect(0f, 0f, Layout.W, Layout.H), alpha(Config.Background, if animated then 0.45f else 0.62f))

  /** Towers beside the backdrop road and enemies marching along it. */
  private def backdropActors(road: Vector[(Int, Int)], cells: Set[(Int, Int)], cols: Int, rows: Int, t: Float,
      ox: Float, oy: Float): Unit =
    val towers = Config.game.towers
    Vector((cols * 2 / 10 + 1, rows * 4 / 10), (cols * 8 / 10 - 1, rows * 4 / 10), (cols * 2 / 10 - 1, rows * 6 / 10),
        (cols * 8 / 10 + 1, rows * 5 / 10)).zipWithIndex.foreach { case ((gx, gy), i) =>
      if !cells.contains((gx, gy)) then
        val c  = towers(i % towers.size).color
        val cx = ox + gx * t + t / 2
        val cy = oy + gy * t + t / 2
        glow(cx, cy, t * 0.7f, c)
        fill(Rect(cx - t * 0.32f, cy - t * 0.32f, t * 0.64f, t * 0.64f), c)
    }
    // enemies marching along the road
    val n       = road.size
    val enemies = Config.game.enemies.filterNot(_.boss)
    for i <- 0 until 9 do
      val pos  = ((time * 0.9f + i * n / 9f) % n + n) % n
      val k    = pos.toInt
      val frac = pos - k
      val (ax, ay) = road(k)
      val (bx, by) = road(math.min(k + 1, n - 1))
      val x = ox + (ax + (bx - ax) * frac) * t + t / 2
      val y = oy + (ay + (by - ay) * frac) * t + t / 2
      val e = enemies(i * 7 % enemies.size)
      val s = 22f + (i % 3) * 6f
      fill(Rect(x - s / 2, y - s / 2, s, s), e.color)

  /** A road winding across the backdrop grid, as cell coordinates in walking order. */
  private def backdropRoad(cols: Int, rows: Int): Vector[(Int, Int)] =
    val corners = Vector(
      (-1, rows * 2 / 10), (cols * 2 / 10, rows * 2 / 10), (cols * 2 / 10, rows * 7 / 10),
      (cols * 8 / 10, rows * 7 / 10), (cols * 8 / 10, rows * 3 / 10), (cols, rows * 3 / 10))
    corners.zip(corners.tail).flatMap { case ((x0, y0), (x1, y1)) =>
      val steps = math.max(math.abs(x1 - x0), math.abs(y1 - y0))
      (0 until steps).map(s => (x0 + (x1 - x0).sign * s, y0 + (y1 - y0).sign * s))
    }

  // -- screens ---------------------------------------------------------------

  def drawMenu(dev: Boolean = false): Unit =
    Config.clearActiveBiome()
    beginScene()
    backdrop(animated = true)
    val items = Layout.MenuItem.values
    val first = Layout.menuButton(items.head)
    val last  = Layout.menuButton(items.last)
    panel(Rect(first.x - 36f, last.y - 36f, first.w + 72f, first.top - last.y + 72f), alpha(Config.PanelColor, 0.82f))

    val title = Messages.t("game.title").toUpperCase
    text(title, Layout.cx, Layout.H - 170f, TextStyle.Title, text0, Align.Center)
    text(Messages.t("menu.tagline"), Layout.cx, Layout.H - 262f, TextStyle.Label, muted, Align.Center)

    items.foreach { item =>
      val look = if item == Layout.MenuItem.Play then Look.Primary else Look.Normal
      button(Layout.menuButton(item), Messages.t(s"menu.${item.toString.toLowerCase}"), look)
    }
    text(Messages.t("menu.footer"), Layout.cx, 34f, TextStyle.Small, muted, Align.Center)
    if dev then drawDevOverlay()
    present()

  /** Display settings: window mode and resolution, applied with APPLY. */
  def drawSettings(draft: DisplaySettings, current: String, fixedResolution: Boolean, dev: Boolean = false): Unit =
    Config.clearActiveBiome()
    beginScene()
    backdrop()
    heading(Messages.t("settings.title"), Messages.t("settings.current", current))
    val rows = Vector(
      (Messages.t("settings.mode"), Messages.t(s"settings.mode.${draft.mode.toString.toLowerCase}"), false),
      (Messages.t("settings.resolution"), draft.resolution, fixedResolution)
    )
    rows.zipWithIndex.foreach { case ((label, value, fixed), i) =>
      val r = Layout.settingsRow(i)
      panel(r)
      text(label, r.x + 32f, r.centerY, TextStyle.Label, text0)
      val v = Layout.settingsValue(i)
      text(value, v.centerX, v.centerY, TextStyle.Stat, if fixed then muted else gold, Align.Center, v.w - 16f)
      if !fixed then
        button(Layout.settingsPrev(i), "<", style = TextStyle.Stat)
        button(Layout.settingsNext(i), ">", style = TextStyle.Stat)
    }
    val note = Layout.settingsRow(rows.size)
    wrapped(Messages.t(s"settings.note.${draft.mode.toString.toLowerCase}"), note.x + 32f, note.top - 20f, note.w - 64f,
      TextStyle.Body, muted)
    button(Layout.settingsApply, Messages.t("settings.apply"), Look.Primary)
    button(Layout.settingsBack, Messages.t("settings.back"))
    if dev then drawDevOverlay()
    present()

  /** Meta progression: spend currency to unlock talent-tree nodes. Rows are
    * grouped by layer (fillers / cores / deeps) in three columns; owned nodes
    * are tinted, locked ones (unmet prereq) and unaffordable ones are dimmed.
    * The fourth column summarizes the active effects those talents grant in-run.
    */
  def drawTalents(data: MetaData, dev: Boolean = false): Unit =
    Config.clearActiveBiome()
    beginScene()
    backdrop()
    heading(Messages.t("talents.title"), Messages.t("talents.balance", data.balance))
    val order   = Layout.talentOrder
    val owned   = data.talents.toSet
    val effects = Meta.effects(data.talents)

    Vector("talents.layer.filler", "talents.layer.core", "talents.layer.deep", "talents.effects").zipWithIndex
      .foreach { (key, col) =>
        text(Messages.t(key), Layout.talentColumnX(col) + 4f, Layout.talentHeaderY, TextStyle.Button, muted)
      }

    order.zipWithIndex.foreach { case (t, i) =>
      val r          = Layout.talentRow(i)
      val isOwned    = owned.contains(t.id)
      val locked     = !t.prereqs.forall(owned.contains)
      val affordable = data.balance >= t.cost
      val buyable    = !isOwned && !locked && affordable
      val base =
        if isOwned then mix(surface, accent, 0.18f)
        else if buyable then Config.ButtonFill
        else mix(surface, Config.PanelColor, 0.4f)
      val c = if buyable && hovered(r) then mix(base, Color.WHITE, 0.14f) else base
      if isOwned then outline(r, 12f, 2f, accent, c) else round(r, 12f, c)
      val nameC = if isOwned then accent else if buyable then text0 else muted
      text(t.name, r.x + 20f, r.top - 24f, TextStyle.Label, nameC, maxW = r.w - 110f)
      val status =
        if isOwned then Messages.t("talents.owned")
        else if locked then Messages.t("talents.locked", Config.talents.find(_.id == t.prereqs.head).fold(t.prereqs.head)(_.name))
        else Messages.t("talents.effectOf." + t.effect.kind, effectAmount(t))
      text(status, r.x + 20f, r.y + 20f, TextStyle.Small, if isOwned then accent else muted, maxW = r.w - 40f)
      if !isOwned then
        text(Messages.t("talents.cost", t.cost), r.right - 18f, r.top - 24f, TextStyle.Button,
          if affordable then gold else faint, Align.Right)
    }

    val box = Layout.talentEffects
    panel(box)
    val lines = Vector(
      Option.when(effects.damageMult != 1f)(Messages.t("talents.damageMult", pct(effects.damageMult))),
      Option.when(effects.rangeMult != 1f)(Messages.t("talents.rangeMult", pct(effects.rangeMult))),
      Option.when(effects.goldMult != 1f)(Messages.t("talents.goldMult", pct(effects.goldMult))),
      Option.when(effects.loadoutSlots > 0)(Messages.t("talents.loadoutSlots", effects.loadoutSlots)),
      Option.when(effects.rerollUnlocked)(Messages.t("talents.reroll")),
      Option.when(effects.extraLives > 0)(Messages.t("talents.extraLives", effects.extraLives)),
      Option.when(effects.startGold > 0)(Messages.t("talents.startGold", effects.startGold)),
      Option.when(effects.activeCores.nonEmpty)(Messages.t("talents.cores", effects.activeCores.mkString(", "))),
      Option.when(effects.activeDeeps.nonEmpty)(Messages.t("talents.deeps", effects.activeDeeps.mkString(", ")))
    ).flatten
    var y = box.top - 36f
    if lines.isEmpty then text(Messages.t("talents.none"), box.x + 24f, y, TextStyle.Body, muted)
    lines.foreach { l =>
      y -= wrapped(l, box.x + 24f, y, box.w - 48f, TextStyle.Body, text0) + 8f
    }

    button(Layout.talentsBack, Messages.t("talents.back"))
    if dev then drawDevOverlay()
    present()

  /** The number a talent's effect line shows: a percentage or a count. */
  private def effectAmount(t: Talent): String =
    t.effect.params.headOption.fold("") { (k, v) =>
      if k == "mult" then s"+${math.round(v * 100)}%" else s"+${math.round(v)}"
    }

  /** Signed percentage of a multiplier relative to 1.0 (1.1 -> +10%). */
  private def pct(mult: Float): String =
    val v = math.round((mult - 1f) * 100f)
    (if v > 0 then s"+$v" else v.toString) + "%"

  /** Pre-run briefing: a painted field with a road, the biome's name and
    * description, the run's twist, and BEGIN. Pure flavor: no sim is stepped.
    */
  def drawBriefing(w: World, dev: Boolean = false): Unit =
    beginScene()
    backdrop()
    val biome = Config.biome(w.biome)
    heading(biome.name, Messages.t(s"briefing.${biome.id}"))

    val art = Layout.briefingArt
    panel(Rect(art.x - 12f, art.y - 12f, art.w + 24f, art.h + 24f))
    fill(art, new Color(0.27f, 0.55f, 0.22f, 1f))
    shapes.setColor(0.21f, 0.45f, 0.18f, 1f)
    var ry = art.y + 30f
    while ry < art.top do
      shapes.rect(art.x, ry, art.w, math.min(26f, art.top - ry))
      ry += 60f
    shapes.setColor(0.18f, 0.40f, 0.15f, 1f)
    Vector((0.1f, 0.15f, 24f), (0.82f, 0.2f, 18f), (0.2f, 0.85f, 20f), (0.7f, 0.9f, 16f)).foreach { (fx, fy, r) =>
      shapes.circle(art.x + art.w * fx, art.y + art.h * fy, r, 20)
    }
    val roadW = 58f
    val steps = 120
    def roadY(t: Float) = (art.y + art.h / 2 + math.sin(t * math.Pi * 1.4 - 0.4) * (art.h / 2 - roadW)).toFloat
    shapes.setColor(0.66f, 0.58f, 0.42f, 1f)
    for i <- 0 until steps do
      val t = i.toFloat / steps
      shapes.rect(art.x + t * art.w - art.w / steps / 2 - 2f, roadY(t) - roadW / 2, art.w / steps + 4f, roadW)
    shapes.setColor(0.85f, 0.80f, 0.62f, 1f)
    for i <- 0 until steps by 5 do
      val t = i.toFloat / steps
      shapes.rect(art.x + t * art.w - 10f, roadY(t) - 3f, 20f, 6f)
    shapes.setColor(0.95f, 0.88f, 0.45f, 1f)
    shapes.circle(art.right - 60f, art.top - 60f, 34f, 32)

    val twists = w.run.twists.flatMap(id => Config.twists.find(_.id == id))
    twists.zipWithIndex.foreach { (tw, i) =>
      text(s"${Messages.t("briefing.twist")}  ${tw.name} — ${Messages.t(s"twist.${tw.id}")}",
        Layout.cx, art.y - 60f - i * 36f, TextStyle.Label, gold, Align.Center, Layout.W - 200f)
    }
    button(Layout.briefingBegin, Messages.t("briefing.begin"), Look.Primary)
    button(Layout.briefingBack, Messages.t("briefing.back"))
    if dev then drawDevOverlay()
    present()

  /** Pre-run loadout picker: every tower as a row; unlocked rows toggle, locked
    * rows show their meta-currency price and are bought by clicking.
    */
  def drawLoadout(run: RunContext, selected: Vector[String], meta: MetaData, dev: Boolean = false): Unit =
    beginScene()
    backdrop()
    val towers = Config.game.towers
    val locked = towers.map(_.name).toSet -- Meta.unlockedTowers(meta)
    heading(Messages.t("loadout.title"),
      Messages.t("loadout.summary", selected.size, Config.Loadout.maxSelected, meta.balance))

    towers.zipWithIndex.foreach { case (t, i) =>
      val r        = Layout.loadoutRow(i)
      val isLocked = locked.contains(t.name)
      val isSel    = selected.contains(t.name)
      val price    = Config.Loadout.unlockCosts.get(t.name)
      val canBuy   = price.exists(_ <= meta.balance)
      val base     = if isLocked then mix(surface, Config.PanelColor, 0.4f) else Config.ButtonFill
      val c        = if hovered(r) && (!isLocked || canBuy) then mix(base, Color.WHITE, 0.12f) else base
      if isSel then outline(r, 12f, 3f, t.color, c) else round(r, 12f, c)
      val sw = Rect(r.x + 18f, r.centerY - 18f, 36f, 36f)
      if !isLocked then glow(sw.centerX, sw.centerY, 30f, t.color)
      fill(sw, if isLocked then alpha(t.color, 0.35f) else t.color)
      text(t.name, r.x + 76f, r.top - 24f, TextStyle.Label, if isLocked then muted else text0)
      text(kindName(t.kind), r.x + 76f, r.y + 20f, TextStyle.Small, muted)
      if isLocked then
        val label = price.fold(Messages.t("loadout.locked"))(c => Messages.t("loadout.unlock", c))
        text(label, r.right - 24f, r.centerY, TextStyle.Button, if canBuy then accent else faint, Align.Right)
      else
        text(s"${t.cost}g", r.right - 24f, r.centerY, TextStyle.Stat, gold, Align.Right)
        if isSel then text(Messages.t("loadout.selected"), r.right - 130f, r.centerY, TextStyle.Small, accent, Align.Right)
    }
    button(Layout.loadoutConfirm, Messages.t("loadout.confirm"), if selected.nonEmpty then Look.Primary else Look.Disabled)
    button(Layout.loadoutBack, Messages.t("loadout.back"))
    if dev then drawDevOverlay()
    present()

  /** Saved games, newest first, one page at a time. A row says who played what,
    * when, and how it ended; clicking it plays the game back.
    */
  def drawReplays(files: Vector[(String, Replay)], page: Int, dev: Boolean = false): Unit =
    beginScene()
    backdrop()
    heading(Messages.t("replay.title"), Messages.t("replay.count", files.size))
    val rows = files.slice(page * Layout.ListPerPage, (page + 1) * Layout.ListPerPage)
    if files.isEmpty then text(Messages.t("replay.empty"), Layout.cx, Layout.H / 2, TextStyle.Label, muted, Align.Center)
    rows.zipWithIndex.foreach { case ((_, r), i) =>
      val row = Layout.listRow(i)
      round(row, 12f, if hovered(row) then mix(Config.ButtonFill, Color.WHITE, 0.12f) else Config.ButtonFill)

      // result badge on the right
      val (label, c) = r.outcome match
        case "WON"     => (Messages.t("replay.outcome.won"), accent)
        case "CLEARED" => (Messages.t("replay.outcome.cleared"), accent)
        case "LOST"    => (Messages.t("replay.outcome.lost"), danger)
        case _         => (Messages.t("replay.outcome.unfinished"), gold)
      val badge = Rect(row.right - 196f, row.centerY - 20f, 176f, 40f)
      outline(badge, 20f, 2f, c, mix(Config.ButtonFill, c, 0.18f))
      text(label, badge.centerX, badge.centerY, TextStyle.Small, c, Align.Center, badge.w - 16f)

      val what =
        if r.kind == "campaign" then Messages.t("replay.what.campaign", r.stat("level").fold("?")(l => (l.toInt + 1).toString))
        else
          val biome = Config.biomes.find(_.id == Config.biomeAt(r.biome, r.level)).fold(r.biome)(_.name)
          Messages.t("replay.what.level", r.level + 1, biome)
      val extras = Vector(
        Option.when(r.setup.talents.nonEmpty)(r.setup.talents.mkString("+")),
        r.setup.relics.headOption, r.setup.augment
      ).flatten
      val title = s"${r.bot}  ·  $what" + (if extras.isEmpty then "" else s"  ·  ${extras.mkString(", ")}")
      text(title, row.x + 24f, row.top - 22f, TextStyle.Label, text0, maxW = badge.x - row.x - 48f)

      val when = java.time.Instant.ofEpochMilli(r.recordedAt).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
      val details = Messages.t("replay.details", when, clock(r.seconds), r.stat("kills").getOrElse("?"),
        r.stat("leaks").getOrElse("?"), r.stat("lives").getOrElse("?"))
      text(details, row.x + 24f, row.y + 18f, TextStyle.Small, muted, maxW = badge.x - row.x - 48f)
    }
    drawPager(page, Layout.listPages(files.size))
    if dev then drawDevOverlay()
    present()

  private def drawPager(page: Int, pages: Int): Unit =
    button(Layout.listPrev, Messages.t("replay.prev"), if page > 0 then Look.Normal else Look.Disabled)
    button(Layout.listBack, Messages.t("replay.back"))
    button(Layout.listNext, Messages.t("replay.next"), if page < pages - 1 then Look.Normal else Look.Disabled)
    text(Messages.t("replay.page", page + 1, pages), Layout.cx, Layout.listPageY, TextStyle.Body, muted, Align.Center)

  /** Logged games summarized per phase, bot, biome and level, one page at a time. */
  def drawProgress(rows: Vector[ProgressLog.Summary], page: Int, dev: Boolean = false): Unit =
    beginScene()
    backdrop()
    heading(Messages.t("progress.title"),
      if rows.isEmpty then "" else Messages.t("progress.games", rows.map(_.games).sum))
    if rows.isEmpty then text(Messages.t("progress.empty"), Layout.cx, Layout.H / 2, TextStyle.Label, muted, Align.Center)
    rows.slice(page * Layout.ListPerPage, (page + 1) * Layout.ListPerPage).zipWithIndex.foreach { case (r, i) =>
      val row = Layout.listRow(i)
      round(row, 12f, Config.ButtonFill)
      val pct = "%.0f".format(100.0 * r.won / math.max(1, r.games))
      text(Messages.t("progress.row1", r.phase, r.bot, r.biome, r.level + 1, r.games, pct),
        row.x + 24f, row.top - 22f, TextStyle.Label, text0, maxW = row.w - 48f)
      text(Messages.t("progress.row2", "%.1f".format(r.kills), "%.1f".format(r.leaks), "%.1f".format(r.waves),
        "%.0f".format(r.secondsWon), "%.0f".format(r.secondsLost)),
        row.x + 24f, row.y + 18f, TextStyle.Small, muted, maxW = row.w - 48f)
    }
    drawPager(page, Layout.listPages(rows.size))
    if dev then drawDevOverlay()
    present()

  def drawRecap(
      won: Boolean,
      level: Int,
      wave: Int,
      lives: Int,
      gold0: Int,
      newEntries: Vector[JournalEntry],
      knownEntries: Vector[JournalEntry],
      death: Option[DeathRecap],
      dev: Boolean = false
  ): Unit =
    beginScene()
    backdrop()
    val stats =
      if won then Messages.t("recap.wonStats", level + 1, lives, gold0)
      else Messages.t("recap.lostStats", level + 1, wave)
    heading(Messages.t(if won then "recap.victory" else "recap.defeat"), stats, if won then accent else danger)

    val box = Rect(Layout.cx - 560f, 170f, 1120f, Layout.subtitleY - 230f)
    panel(box)
    var y = box.top - 40f
    death.foreach { d =>
      text(Messages.t("recap.deathResisted", d.leakPct, kindName(d.resistedKind)), box.x + 32f, y, TextStyle.Label, danger,
        maxW = box.w - 64f)
      y -= 36f
      if d.missingCounter then
        text(Messages.t("recap.deathMissing", kindName(d.counterKind)), box.x + 32f, y, TextStyle.Label, danger,
          maxW = box.w - 64f)
        y -= 36f
      y -= 12f
    }
    text(Messages.t("recap.discoveries"), box.x + 32f, y, TextStyle.Button, muted)
    y -= 44f
    def line(s: String, c: Color): Unit =
      if y > box.y + 24f then
        text(s, box.x + 32f, y, TextStyle.Body, c, maxW = box.w - 64f)
        y -= 32f
    newEntries.foreach { e =>
      val (name, detail) = entryParts(e)
      line(s"${Messages.t("recap.new")}  $name${if detail.isEmpty then "" else s" — $detail"}", text0)
    }
    knownEntries.foreach { e =>
      val (name, detail) = entryParts(e)
      line(s"${Messages.t("recap.known")}  $name${if detail.isEmpty then "" else s" — $detail"}", muted)
    }
    if newEntries.isEmpty && knownEntries.isEmpty then line(Messages.t("recap.none"), muted)

    button(Layout.recapRetry, Messages.t(if won then "recap.again" else "recap.retry"), Look.Primary)
    button(Layout.recapMenu, Messages.t("recap.menu"))
    if dev then drawDevOverlay()
    present()

  /** The game; with `replay` the side panel shows playback controls instead of key hints. */
  def drawGame(w: World, hover: Option[GridPos], fast: Boolean, selected: TowerSpec, notice: Option[String] = None,
      dev: Boolean = false, replay: Option[Playback] = None): Unit =
    Config.setActive(w.biome, w.level)
    beginScene()
    drawScene(w, hover, fast, selected, notice, replay)
    if dev then drawDevOverlay()
    present()

  /** The between-level draft: the offers as cards, the reroll button and the gold. */
  def drawDraft(w: World, dev: Boolean = false): Unit =
    Config.setActive(w.biome, w.level)
    beginScene()
    backdrop()
    val d         = w.draft.get
    val unlocked  = Meta.effects(w.run.talents).rerollUnlocked
    val canReroll = unlocked && w.gold >= d.rerollCost
    heading(Messages.t("draft.title"), Messages.t("draft.subtitle", w.level + 1))

    d.offers.zipWithIndex.foreach { case (offer, i) =>
      val r = Layout.draftCard(i, d.offers.size)
      val (kind, note, name, rarity, effect) = offer match
        case DraftOffer.Relic(id) =>
          val rl = Config.relic(id)
          (Messages.t("draft.relic"), Messages.t("draft.relicNote"), rl.name, Some(rl.rarity.toString), rl.effect)
        case DraftOffer.Augment(id) =>
          val a = Config.augment(id)
          (Messages.t("draft.augment"), Messages.t("draft.augmentNote"), a.name, None, a.effect)
      val rc = rarity match
        case Some("Legendary") => gold
        case Some("Rare")      => Config.ShotShockColor
        case _                 => muted
      val c = if hovered(r) then mix(Config.ButtonFill, Color.WHITE, 0.12f) else Config.ButtonFill
      outline(r, 18f, 2.5f, if hovered(r) then text0 else mix(rc, Config.ButtonFill, 0.4f), c)
      text(kind.toUpperCase, r.centerX, r.top - 34f, TextStyle.Button, muted, Align.Center)
      text(note, r.centerX, r.top - 64f, TextStyle.Small, muted, Align.Center)
      text(name, r.centerX, r.top - 118f, TextStyle.Card, text0, Align.Center, r.w - 40f)
      val (desc, works) = boonText(effect)
      wrapped(desc, r.x + 28f, r.top - 168f, r.w - 56f, TextStyle.Body, if works then text0 else muted, Align.Center)
      rarity.foreach(rr => text(Messages.t(s"draft.rarity.$rr").toUpperCase, r.centerX, r.y + 30f, TextStyle.Button, rc,
        Align.Center))
    }
    button(Layout.draftReroll,
      if !unlocked then Messages.t("draft.rerollLocked") else Messages.t("draft.reroll", d.rerollCost),
      if canReroll then Look.Normal else Look.Disabled, TextStyle.Label)
    text(Messages.t("draft.gold", w.gold), Layout.cx, 70f, TextStyle.Stat, gold, Align.Center)
    if dev then drawDevOverlay()
    present()

  /** What a relic or augment does, with its numbers, and whether the game implements it. */
  private def boonText(e: Effect): (String, Boolean) =
    def p(k: String) = e.params.getOrElse(k, 0f)
    def pct(mult: Float) = math.round((mult - 1f) * 100).toString
    def num(x: Float) = if x == x.round.toFloat then x.round.toString else x.toString
    e.kind match
      case "squareDeal"    => (Messages.t("boon.squareDeal", pct(p("goldMult"))), true)
      case "boxOffice"     => (Messages.t("boon.boxOffice", num(p("killRewardBonus"))), true)
      case "sharpenedEdge" => (Messages.t("boon.sharpenedEdge", pct(p("damageMult"))), true)
      case "vertigo"       => (Messages.t("boon.vertigo", math.round(p("slowAmount") * 100).toString, num(p("slowDuration"))), true)
      case _               => (Messages.t("boon.placeholder"), false)

  /** Frozen game view under a dim overlay plus the pause panel and its buttons. */
  def drawPaused(w: World, fast: Boolean, selected: TowerSpec, dev: Boolean = false): Unit =
    Config.setActive(w.biome, w.level)
    beginScene()
    drawScene(w, None, fast, selected, None)
    flush()
    fill(Rect(0f, 0f, Layout.W, Layout.H), alpha(Color.BLACK, 0.6f))
    panel(Layout.pausePanel, Config.PanelColor)
    text(Messages.t("pause.title"), Layout.cx, Layout.pausePanel.top - 90f, TextStyle.Heading, text0, Align.Center)
    button(Layout.pauseButton(0), Messages.t("pause.resume"), Look.Primary)
    button(Layout.pauseButton(1), Messages.t("pause.settings"))
    button(Layout.pauseButton(2), Messages.t("pause.exit"))
    if dev then drawDevOverlay()
    present()

  /** Developer overlay: a panel of cheat buttons drawn on top of everything. */
  def drawDevOverlay(): Unit =
    flush()
    val p = Layout.devPanel
    panel(p, alpha(Color.BLACK, 0.85f))
    text(Messages.t("dev.title"), p.centerX, p.top - 40f, TextStyle.Button, gold, Align.Center)
    Layout.DevItem.values.foreach { item =>
      val key = item match
        case Layout.DevItem.Gold100       => "dev.gold100"
        case Layout.DevItem.Gold1000      => "dev.gold1000"
        case Layout.DevItem.Meta100       => "dev.meta100"
        case Layout.DevItem.UnlockTowers  => "dev.unlockTowers"
        case Layout.DevItem.UnlockTalents => "dev.unlockTalents"
      button(Layout.devButton(item), Messages.t(key), style = TextStyle.Small)
    }

  // -- in game ------------------------------------------------------------------------

  /** The in-game scene: board, actors, shots, overlays and the HUD panel. */
  private def drawScene(w: World, hover: Option[GridPos], fast: Boolean, selected: TowerSpec,
      notice: Option[String], replay: Option[Playback] = None): Unit =
    val b = Layout.board
    // a soft frame around the board
    round(Rect(b.x - 10f, b.y - 10f, b.w + 20f, b.h + 20f), 10f, mix(Config.GridLine, Config.PathColor, 0.25f))

    onBoard(ShapeType.Filled) {
      drawBoard(w.level)
      drawTowers(w)
      drawEnemies(w)
      drawShots(w)
      drawSparks()
      drawHover(w, hover, selected)
    }
    onBoard(ShapeType.Line) {
      drawRanges(w, hover, selected)
      drawAuras(w)
    }
    drawFloating()
    drawHud(w, fast, selected, replay)
    notice.foreach { n =>
      val a = Layout.boardArea
      text(n, a.centerX, a.y + 4f, TextStyle.Label, gold, Align.Center, a.w)
    }

  /** Draws `body` in simulation coordinates, scaled and moved onto the board. */
  private def onBoard(kind: ShapeType)(body: => Unit): Unit =
    val b = Layout.board
    val k = Layout.boardScale
    shapes.end()
    shapes.setTransformMatrix(new Matrix4().translate(b.x, b.y, 0f).scale(k, k, 1f))
    shapes.begin(kind)
    body
    shapes.end()
    shapes.setTransformMatrix(identity)
    shapes.begin(ShapeType.Filled)

  private def drawFloating(): Unit =
    floating.foreach { f =>
      val t    = f.life / f.maxLife
      val rise = (1f - t) * 16f
      val c    = new Color(f.color)
      c.a      = math.min(1f, t * 2f)
      val (x, y) = Layout.toUi(f.x, f.y)
      text(f.label, x, y + rise, if f.big then TextStyle.Label else TextStyle.Popup, c, Align.Center)
    }

  // -- board pieces (simulation coordinates) --------------------------------------------

  /** The board is filled with the grid-line color, then cells are drawn inset so
    * the grid line shows through as the gap between them. The field cells are lit
    * by the ambient (global-illumination) term.
    */
  private def drawBoard(level: Int): Unit =
    val cell = Config.CellPx
    val gap  = cell * 0.035f
    val path = Grid.pathCells(level)
    shapes.setColor(Config.GridLine)
    shapes.rect(0f, 0f, Config.GridSize * cell, Config.GridSize * cell)
    for gx <- 0 until Config.GridSize; gy <- 0 until Config.GridSize do
      val base = if path.contains(GridPos(gx, gy)) then Config.PathColor else Config.BuildColor
      shapes.setColor(lit(base))
      shapes.rect(gx * cell + gap, gy * cell + gap, cell - 2 * gap, cell - 2 * gap)
    Grid.puddles(level).foreach { case (x, y, r) =>
      shapes.setColor(Config.ShotShockColor.r, Config.ShotShockColor.g, Config.ShotShockColor.b, 0.22f)
      shapes.circle(x, y, r, 32)
    }

  private def drawTowers(w: World): Unit =
    val cell = Config.CellPx
    w.towers.foreach { t =>
      val cx = t.cell.centerX + Config.ShadowOffsetX
      val cy = t.cell.centerY + Config.ShadowOffsetY
      val r  = cell * 0.42f * Config.ShadowScale
      val sc = Config.ShadowColor
      shapes.setColor(sc.r, sc.g, sc.b, Config.ShadowAlpha * 0.5f)
      shapes.circle(cx, cy, r, 20)
      shapes.setColor(sc.r, sc.g, sc.b, Config.ShadowAlpha)
      shapes.circle(cx, cy, r * 0.6f, 16)
      glow(t.cell.centerX, t.cell.centerY, cell * 0.65f, t.spec.color)
      val inset = cell * 0.12f
      shapes.setColor(t.spec.color)
      shapes.rect(t.cell.x * cell + inset, t.cell.y * cell + inset, cell - 2 * inset, cell - 2 * inset)
      shapes.setColor(mix(t.spec.color, Color.WHITE, 0.35f))
      shapes.rect(t.cell.x * cell + inset, (t.cell.y + 1) * cell - inset - cell * 0.07f, cell - 2 * inset, cell * 0.07f)
      t.augment.foreach { _ =>
        shapes.setColor(gold)
        shapes.circle(t.cell.centerX, t.cell.centerY, cell * 0.1f, 12)
      }
      // While an augment is held, mark every socketable tower (build-hover color).
      if w.pendingAugment.isDefined && t.augment.isEmpty then
        shapes.setColor(Config.HoverOk)
        shapes.rect(t.cell.x * cell, t.cell.y * cell, cell, cell)
    }

  private def drawSparks(): Unit =
    val c = Config.SparkColor
    val s = Config.SparkSize
    sparks.foreach { p =>
      shapes.setColor(c.r, c.g, c.b, math.min(1f, p.life / p.maxLife))
      shapes.rect(p.x - s / 2f, p.y - s / 2f, s, s)
    }

  /** How far an enemy is drawn beside the road's center line, so a wave looks
    * like a crowd rather than a queue. Drawing only: the simulation still moves,
    * targets and hits it on the center line, so rules and replays are unchanged.
    * Each enemy keeps its own side and distance (from its id); the offset turns
    * smoothly through corners and is smaller for enemies that fill the road.
    */
  private def scatter(e: Enemy): (Float, Float) =
    val cell = Config.CellPx
    val room = math.max(0f, (cell * 0.84f - e.spec.size) / 2f)
    if room <= 0f then (0f, 0f)
    else
      val h    = java.lang.Long.hashCode(e.id * 0x9E3779B97F4A7C15L)
      val t    = ((h >>> 8) & 0xffff) / 65535f * 2f - 1f
      val (ax, ay) = Grid.positionAt(e.progress - cell * 0.5f, e.level, e.lane)
      val (bx, by) = Grid.positionAt(e.progress + cell * 0.5f, e.level, e.lane)
      val dx  = bx - ax
      val dy  = by - ay
      val len = math.sqrt(dx * dx + dy * dy).toFloat
      if len < 1e-3f then (0f, 0f) else (-dy / len * t * room, dx / len * t * room)

  /** Where an enemy is drawn: its simulation position plus its [[scatter]]. */
  private def drawnPos(e: Enemy): (Float, Float) =
    val (x, y)   = e.pos
    val (ox, oy) = scatter(e)
    (x + ox, y + oy)

  private def drawEnemies(w: World): Unit =
    w.enemies.foreach { e =>
      val size = e.spec.size
      val (ex, ey) = drawnPos(e)
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

      val barW = math.max(20f, size * 1.1f)
      val frac = math.max(0f, math.min(1f, e.hp / e.maxHp))
      shapes.setColor(Config.HpBarBack)
      shapes.rect(ex - barW / 2, ey + size / 2 + 3f, barW, 3.5f)
      shapes.setColor(Config.HpBarFill)
      shapes.rect(ex - barW / 2, ey + size / 2 + 3f, barW * frac, 3.5f)
    }

  /** Ring around each aura carrier so its buff radius is legible. */
  private def drawAuras(w: World): Unit =
    w.enemies.foreach { e =>
      e.spec.aura.foreach { a =>
        val (ex, ey) = drawnPos(e)
        shapes.setColor(e.spec.color)
        shapes.circle(ex, ey, a.radius * Config.CellPx, 48)
      }
    }

  private def shotColor(kind: DamageKind): Color = kind match
    case DamageKind.Kinetic   => Config.ShotColor
    case DamageKind.Fire      => Config.ShotFireColor
    case DamageKind.Slow      => Config.ShotFrostColor
    case DamageKind.Splash    => Config.ShotSplashColor
    case DamageKind.Explosion => Config.ShotBoomColor
    case DamageKind.Shock     => Config.ShotShockColor

  /** Shots fly at their target's simulation position; near the end they bend
    * towards where the target is drawn (see [[scatter]]) so hits look right.
    */
  private def drawShots(w: World): Unit =
    val byId = w.enemies.map(e => e.id -> e).toMap
    w.shots.foreach { p =>
      val (x, y) = p.dest match
        case Some(_) => (p.x, p.y)
        case None =>
          byId.get(p.targetId).fold((p.x, p.y)) { e =>
            val (tx, ty) = e.pos
            val (ox, oy) = scatter(e)
            val dist = math.sqrt((tx - p.x) * (tx - p.x) + (ty - p.y) * (ty - p.y)).toFloat
            val f    = math.max(0f, math.min(1f, 1f - dist / (Config.CellPx * 1.5f)))
            (p.x + ox * f, p.y + oy * f)
          }
      val c = shotColor(p.kind)
      glow(x, y, 10f, c)
      shapes.setColor(c)
      shapes.rect(x - 3f, y - 3f, 6f, 6f)
    }

  private def drawHover(w: World, hover: Option[GridPos], selected: TowerSpec): Unit =
    hover.foreach { cell =>
      shapes.setColor(if Sim.canBuild(w, cell, selected.name) then Config.HoverOk else Config.HoverBad)
      shapes.rect(cell.x * Config.CellPx, cell.y * Config.CellPx, Config.CellPx, Config.CellPx)
    }

  /** Range ring of the hovered tower, or of the selected tower previewed on a
    * hovered empty cell. Other towers show no ring, so the board stays readable.
    */
  private def drawRanges(w: World, hover: Option[GridPos], selected: TowerSpec): Unit =
    val hoveredTower = hover.flatMap(c => w.towers.find(_.cell == c))
    hoveredTower.foreach { t =>
      shapes.setColor(t.spec.color)
      shapes.circle(t.cell.centerX, t.cell.centerY, t.spec.range * Config.CellPx, 72)
    }
    hover.filter(c => hoveredTower.isEmpty && Grid.isBuildable(c, w.level)).foreach { c =>
      shapes.setColor(selected.color)
      shapes.circle(c.centerX, c.centerY, selected.range * Config.CellPx, 72)
    }

  // -- HUD ---------------------------------------------------------------------------

  /** Top of the HUD's tower list; [[Layout.hudTower]] rows hang below it. */
  def hudTowersTop: Float = Layout.H - 330f

  private def drawHud(w: World, fast: Boolean, selected: TowerSpec, replay: Option[Playback]): Unit =
    val p  = Layout.panel
    val x  = p.x + 28f
    val iw = p.w - 56f
    fill(p, Config.PanelColor)
    fill(Rect(p.x, 0f, 2f, p.h), mix(Config.PanelColor, Config.ButtonFill, 0.8f))

    // level
    val lvl = Config.levelAt(w.level)
    text(Messages.t("hud.level", w.level + 1, Config.levelCount).toUpperCase, x, p.top - 44f, TextStyle.Button, muted)
    text(lvl.name, x, p.top - 84f, TextStyle.Label, text0, maxW = iw)

    // gold / lives / wave tiles
    val tileW = (iw - 24f) / 3
    Vector(
      (Messages.t("hud.gold"), w.gold.toString, gold),
      (Messages.t("hud.lives"), w.lives.toString, danger),
      (Messages.t("hud.wave"), s"${w.wave}/${Waves.waveCount(w.level)}", text0)
    ).zipWithIndex.foreach { case ((caption, value, c), i) =>
      val r = Rect(x + i * (tileW + 12f), p.top - 236f, tileW, 110f)
      round(r, 12f, surface)
      text(value, r.centerX, r.centerY + 14f, TextStyle.Stat, c, Align.Center, r.w - 12f)
      text(caption, r.centerX, r.y + 22f, TextStyle.Small, muted, Align.Center)
    }

    // towers
    val pool = Config.LoadoutPool(w.run.loadout)
    text(Messages.t("hud.towers"), x, hudTowersTop + 22f, TextStyle.Button, muted)
    pool.zipWithIndex.foreach { case (t, i) =>
      val r      = Layout.hudTower(i, hudTowersTop)
      val isSel  = t.name == selected.name
      val afford = w.gold >= t.cost
      val base   = if isSel then mix(surface, t.color, 0.22f) else surface
      val c      = if hovered(r) then mix(base, Color.WHITE, 0.08f) else base
      if isSel then outline(r, 10f, 2f, t.color, c) else round(r, 10f, c)
      text((i + 1).toString, r.x + 22f, r.centerY, TextStyle.Button, if isSel then t.color else muted, Align.Center)
      fill(Rect(r.x + 44f, r.centerY - 12f, 24f, 24f), if afford then t.color else alpha(t.color, 0.35f))
      text(t.name, r.x + 84f, r.centerY, TextStyle.Label, if afford then text0 else muted, maxW = r.w - 180f)
      text(s"${t.cost}g", r.right - 16f, r.centerY, TextStyle.Button, if afford then gold else faint, Align.Right)
    }

    // wave status, twist and held augment
    var y = hudTowersTop - pool.size * 62f - 40f
    def para(s: String, c: Color, style: TextStyle = TextStyle.Body): Unit =
      if y > (if replay.isDefined then Layout.replayTop else 150f) then y -= wrapped(s, x, y, iw, style, c) + 10f
    if w.inBreak then para(Messages.t("hud.nextWave", f"${w.breakTimer}%.1f"), text0, TextStyle.Label)
    else para(Messages.t("hud.spawning", w.spawnQueue.size, w.enemies.size), text0, TextStyle.Label)
    val waveNum = if w.inBreak then math.min(w.wave + 1, Waves.waveCount(w.level)) else w.wave
    para(Messages.t("hud.types", Waves.spec(w.level, waveNum).composition.map(_._1).mkString(", ")), muted)
    w.run.twists.flatMap(id => Config.twists.find(_.id == id)).foreach { tw =>
      para(s"${Messages.t("briefing.twist")} ${tw.name} — ${Messages.t(s"twist.${tw.id}")}", gold, TextStyle.Small)
    }
    w.pendingAugment.foreach { id =>
      para(s"${Config.augment(id).name} — ${Messages.t("draft.socketHint")}", accent, TextStyle.Small)
    }

    replay match
      case Some(pb) => drawReplayControls(pb)
      case None     => drawKeyHints(fast)

  private def drawKeyHints(fast: Boolean): Unit =
    val x = Layout.panel.x + 28f
    val hints = Vector(
      (if fast then Messages.t("hud.fastOn", Config.FastForward) else Messages.t("hud.fast"), if fast then gold else muted),
      (Messages.t("hud.pause"), muted),
      (Messages.t("hud.reload"), muted)
    )
    hints.zipWithIndex.foreach { case ((s, c), i) => text(s, x, 112f - i * 34f, TextStyle.Small, c) }

  /** Playback controls: speed, elapsed time, a progress bar to click, and the buttons. */
  private def drawReplayControls(pb: Playback): Unit =
    val bar = Layout.replayProgress
    val x   = bar.x
    fill(Rect(Layout.panel.x + 2f, 0f, Layout.PanelW - 2f, Layout.replayTop - 14f), mix(Config.PanelColor, surface, 0.5f))
    val state =
      if pb.finished then Messages.t("replay.finished")
      else if pb.paused then Messages.t("replay.paused")
      else Messages.t("replay.playing")
    text(Messages.t("replay.title.short"), x, bar.top + 34f, TextStyle.Button, muted)
    text(s"${speedLabel(pb.speed)}  ·  $state", bar.right, bar.top + 34f, TextStyle.Button,
      if pb.paused || pb.finished then gold else accent, Align.Right)
    round(bar, 7f, Config.PanelColor)
    if pb.progress > 0f then round(Rect(bar.x, bar.y, math.max(14f, bar.w * pb.progress), bar.h), 7f, accent)
    if hovered(bar) then
      val at = math.max(bar.x, math.min(bar.right, mouse._1))
      fill(Rect(at - 1.5f, bar.y - 6f, 3f, bar.h + 12f), text0)
    text(clock(pb.index * Config.MaxStepDt), x, bar.y - 18f, TextStyle.Small, muted)
    text(clock(pb.replay.seconds), bar.right, bar.y - 18f, TextStyle.Small, muted, Align.Right)
    button(Layout.replaySlower, "−", style = TextStyle.Heading)
    button(Layout.replayPause, Messages.t(if pb.paused || pb.finished then "replay.play" else "replay.pause"), Look.Primary)
    button(Layout.replayFaster, "+", style = TextStyle.Heading)
    button(Layout.replayExit, Messages.t("replay.exit"))
    wrapped(Messages.t("replay.keys"), x, 52f, Layout.PanelW - 56f, TextStyle.Small, muted)

  private def speedLabel(speed: Double): String =
    if speed >= 1.0 then s"${speed.toInt}x" else s"${speed}x"

  /** Seconds as m:ss. */
  private def clock(seconds: Float): String =
    val t = math.max(0, seconds.toInt)
    f"${t / 60}%d:${t % 60}%02d"

  // -- bloom ------------------------------------------------------------------

  private def ensureBloom(): Unit =
    val w = Gdx.graphics.getBackBufferWidth
    val h = Gdx.graphics.getBackBufferHeight
    if sceneFbo == null || w != fboW || h != fboH then
      Seq(sceneFbo, bloomA, bloomB).filter(_ != null).foreach(_.dispose())
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
    Gdx.gl.glViewport(0, 0, fboW, fboH)

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
    Gdx.gl.glViewport(0, 0, fboW, fboH)
    Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    combine.bind()
    combine.setUniformi("u_scene", 0)
    combine.setUniformi("u_bloom", 1)
    combine.setUniformf("u_strength", BloomStrength)
    val vc = Config.VignetteColor
    combine.setUniformf("u_vigColor", vc.r, vc.g, vc.b)
    combine.setUniformf("u_vigStrength", Config.VignetteStrength)
    combine.setUniformf("u_vigRadius", Config.VignetteRadius)
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
      |uniform vec3 u_vigColor;
      |uniform float u_vigStrength;
      |uniform float u_vigRadius;
      |void main() {
      |  vec4 s = texture2D(u_scene, v_texCoords);
      |  vec4 b = texture2D(u_bloom, v_texCoords);
      |  vec3 col = s.rgb + b.rgb * u_strength;
      |  float d = distance(v_texCoords, vec2(0.5));
      |  float vig = 1.0 - u_vigStrength * smoothstep(u_vigRadius, 1.0, d);
      |  col = mix(u_vigColor, col, clamp(vig, 0.0, 1.0));
      |  gl_FragColor = vec4(col, 1.0);
      |}""".stripMargin
