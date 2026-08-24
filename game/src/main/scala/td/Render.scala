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
  private val rng      = new scala.util.Random()

  // -- camera shake (life loss) ----------------------------------------------
  private var shakeTime: Float = 0f
  private var shakeMag: Float  = 0f

  // -- hit sparks -------------------------------------------------------------
  private case class Spark(x: Float, y: Float, vx: Float, vy: Float, life: Float, maxLife: Float)
  private val sparks = scala.collection.mutable.ArrayBuffer[Spark]()

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

  /** Register a landed hit as a floating damage/status label + a spark burst. */
  def feedHits(hits: Vector[HitFeedback]): Unit =
    hits.foreach { h =>
      val (label, color) =
        if h.resistFrac >= 0.99f then (Messages.t("popup.immune"), Config.EnemyColor)
        else if h.resistFrac > 0f then (Messages.t("popup.resist"), Config.EnemyColor)
        else (math.round(h.dealt).toString, Config.TextColor)
      floating += Floating(h.x, h.y + 14f, label, color, PopupLife, PopupLife, big = false)
      // sparks: a small radial burst at the impact point
      val n = Config.SparkCount
      for i <- 0 until n do
        val ang  = rng.nextFloat() * math.Pi * 2f
        val spd  = Config.SparkSpeed * (0.4f + rng.nextFloat() * 0.6f)
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
    // The board (and thus the world size) can differ per biome (the tutorial is
    // smaller), so re-sync the viewport's world dimensions before drawing.
    viewport.setWorldSize(Config.WorldW, Config.WorldH)
    // camera shake: jitter the centered ortho camera while a shake is active
    if shakeTime > 0f then
      val k = shakeMag * (shakeTime / math.max(1e-3f, ShakeDurationRef))
      camera.position.set(Config.WorldW / 2f + (rng.nextFloat() * 2f - 1f) * k,
                          Config.WorldH / 2f + (rng.nextFloat() * 2f - 1f) * k, 0f)
    else camera.position.set(Config.WorldW / 2f, Config.WorldH / 2f, 0f)
    viewport.apply()
    ScreenUtils.clear(Config.Background)
    Gdx.gl.glEnable(GL20.GL_BLEND)
    shapes.setProjectionMatrix(camera.combined)
    batch.setProjectionMatrix(camera.combined)

  /** Ambient-only global illumination: scale a surface color by the ambient
    * light tint * intensity. White at intensity 1 is a no-op.
    */
  private def lit(c: Color): Color =
    val a = Config.LightingAmbient
    val k = Config.LightingIntensity
    new Color(c.r * a.r * k, c.g * a.g * k, c.b * a.b * k, c.a)

  /** Triggers a camera shake (called when a life is lost). */
  def shake(): Unit =
    shakeTime = Config.ShakeDuration
    shakeMag  = Config.ShakeMagnitude

  private def ShakeDurationRef: Float = Config.ShakeDuration

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

  def drawMenu(dev: Boolean = false): Unit =
    Config.clearActiveBiome()
    beginScene()
    shapes.begin(ShapeType.Filled)
    button(Ui.Play)
    button(Ui.Tutorial)
    button(Ui.Talents)
    shapes.end()

    val towers = Config.game.towers.size
    batch.begin()
    centered(Messages.t("game.title"), Config.WorldW / 2, 440f, 3f)
    centered(s"${Config.GridSize} x ${Config.GridSize} · hold the line", Config.WorldW / 2, 390f, 1.2f, Config.DimText)
    buttonLabel(Ui.Play, "PLAY")
    buttonLabel(Ui.Tutorial, "TUTORIAL")
    buttonLabel(Ui.Talents, "TALENTS")
    centered(s"1-$towers to pick a tower · click green to build · F to fast-forward · R to reload config · ESC to pause",
      Config.WorldW / 2, 120f, 1f, Config.DimText)
    batch.end()
    if dev then drawDevOverlay()
    present()

  /** Meta progression: spend currency to unlock talent-tree nodes. Rows are
    * grouped by layer (fillers / cores / deeps) in three columns; owned nodes
    * are tinted, locked ones (unmet prereq) and unaffordable ones are dimmed.
    * The footer summarizes the active effects those talents grant in-run.
    */
  def drawTalents(data: MetaData, dev: Boolean = false): Unit =
    Config.clearActiveBiome()
    beginScene()
    val order  = Ui.talentOrder
    val owned  = data.talents.toSet
    val effects = Meta.effects(data.talents)

    shapes.begin(ShapeType.Filled)
    order.zipWithIndex.foreach { case (t, i) =>
      val r         = Ui.TalentRow(i)
      val isOwned   = owned.contains(t.id)
      val locked    = !t.prereqs.forall(owned.contains)
      val affordable = data.balance >= t.cost
      val fill =
        if isOwned then Config.PanelColor
        else if locked then Config.PanelColor
        else if affordable then Config.ButtonFill
        else Config.PanelColor
      box(r, fill)
      if isOwned then
        shapes.setColor(Config.BuffTint)
        shapes.rect(r.x, r.y, 4f, r.h)
    }
    button(Ui.TalentsBack)
    shapes.end()

    batch.begin()
    centered(Messages.t("talents.title"), Config.WorldW / 2, 600f, 3f)
    centered(Messages.t("talents.balance", data.balance), Config.WorldW / 2, 560f, 1.4f, Config.ShotColor)

    order.zipWithIndex.foreach { case (t, i) =>
      val r         = Ui.TalentRow(i)
      val isOwned   = owned.contains(t.id)
      val locked    = !t.prereqs.forall(owned.contains)
      val affordable = data.balance >= t.cost
      val labelColor =
        if isOwned then Config.BuffTint
        else if locked || !affordable then Config.DimText
        else Config.TextColor
      val status =
        if isOwned then Messages.t("talents.owned")
        else if locked then Messages.t("talents.locked", t.prereqs.head)
        else Messages.t("talents.cost", t.cost)
      text(s"${t.name}", r.x + 14f, r.centerY + 8f, 1.3f, labelColor)
      text(status, r.x + 14f, r.y + 10f, 0.9f, Config.DimText)
    }

    centered(Messages.t("talents.effects"), Config.WorldW / 2, 150f, 1.6f)
    var y = 122f
    def effLine(s: String): Unit =
      text(s, Config.WorldW / 2 - 190f, y, 1.1f, Config.TextColor)
      y -= 20f
    if effects.damageMult != 1f then effLine(Messages.t("talents.damageMult", pct(effects.damageMult)))
    if effects.rangeMult  != 1f then effLine(Messages.t("talents.rangeMult", pct(effects.rangeMult)))
    if effects.goldMult   != 1f then effLine(Messages.t("talents.goldMult", pct(effects.goldMult)))
    if effects.loadoutSlots > 0 then effLine(Messages.t("talents.loadoutSlots", effects.loadoutSlots))
    if effects.rerollUnlocked then effLine(Messages.t("talents.reroll"))
    if effects.activeCores.nonEmpty then effLine(Messages.t("talents.cores", effects.activeCores.mkString(", ")))
    if effects.activeDeeps.nonEmpty then effLine(Messages.t("talents.deeps", effects.activeDeeps.mkString(", ")))
    if y == 122f then effLine(Messages.t("talents.none"))

    buttonLabel(Ui.TalentsBack, Messages.t("talents.back"))
    batch.end()
    if dev then drawDevOverlay()
    present()

  /** Signed percentage of a multiplier relative to 1.0 (1.1 -> +10%). */
  private def pct(mult: Float): String =
    val v = math.round((mult - 1f) * 100f)
    (if v > 0 then s"+$v" else v.toString) + "%"

  /** Pre-run briefing: a vague painted field with a road, the biome's name, a
    * one-line description, and a BEGIN button. Pure flavor — no sim is stepped.
    */
  def drawBriefing(w: World, dev: Boolean = false): Unit =
    beginScene()

    val biome = Config.biome(w.run.biome)
    val art   = Rect(Config.WorldW / 2 - 200f, 200f, 400f, 280f)

    // -- the painted field -----------------------------------------------------
    shapes.begin(ShapeType.Filled)
    // grass
    box(art, new Color(0.27f, 0.55f, 0.22f, 1f))
    // plowed rows: darker horizontal stripes
    shapes.setColor(0.21f, 0.45f, 0.18f, 1f)
    var ry = art.y + 18f
    while ry < art.y + art.h do
      shapes.rect(art.x, ry, art.w, 16f)
      ry += 36f
    // a few bushes for texture
    shapes.setColor(0.18f, 0.40f, 0.15f, 1f)
    shapes.circle(art.x + 70f,  art.y + 60f,  14f)
    shapes.circle(art.x + 330f, art.y + 80f,  11f)
    shapes.circle(art.x + 120f, art.y + 240f, 12f)
    // the road: a band winding across the field, edge to edge
    val roadW   = 34f
    val steps   = 80
    shapes.setColor(0.66f, 0.58f, 0.42f, 1f)
    for i <- 0 until steps do
      val t   = i.toFloat / steps
      val x   = art.x + t * art.w
      val cy  = (art.y + art.h / 2 + math.sin(t * math.Pi * 1.4 - 0.4) * (art.h / 2 - roadW)).toFloat
      shapes.rect(x - art.w / steps / 2 - 2f, cy - roadW / 2, art.w / steps + 4f, roadW)
    // road center dashes
    shapes.setColor(0.85f, 0.80f, 0.62f, 1f)
    for i <- 0 until steps by 4 do
      val t   = i.toFloat / steps
      val x   = art.x + t * art.w
      val cy  = (art.y + art.h / 2 + math.sin(t * math.Pi * 1.4 - 0.4) * (art.h / 2 - roadW)).toFloat
      shapes.rect(x - 6f, cy - 2f, 12f, 4f)
    // a sun in the corner
    shapes.setColor(0.95f, 0.88f, 0.45f, 1f)
    shapes.circle(art.x + art.w - 36f, art.y + 36f, 20f)
    // buttons sit on the same shape pass as the art
    button(Ui.BriefingBegin)
    button(Ui.BriefingBack)
    shapes.end()

    batch.begin()
    centered(biome.name, Config.WorldW / 2, 540f, 3f)
    centered(Messages.t(s"briefing.${biome.id}"), Config.WorldW / 2, 470f, 1.3f, Config.DimText)

    w.run.twists.foreach { id =>
      Config.twists.find(_.id == id).foreach { tw =>
        centered(s"${Messages.t("briefing.twist")}: ${tw.name} - ${Messages.t(s"twist.${tw.id}")}",
          Config.WorldW / 2, 430f, 1f, Config.ShotColor)
      }
    }

    buttonLabel(Ui.BriefingBegin, Messages.t("briefing.begin"))
    buttonLabel(Ui.BriefingBack, Messages.t("briefing.back"))
    batch.end()
    if dev then drawDevOverlay()
    present()

  /** Pre-run loadout picker: every tower as a row; unlocked rows are clickable
    * and toggle a highlight border, locked rows render as a "?" and ignore
    * clicks. The run starts with exactly the selected towers in its loadout.
    */
  def drawLoadout(run: RunContext, selected: Vector[String], dev: Boolean = false): Unit =
    beginScene()
    val towers  = Config.game.towers
    val locked  = Config.Loadout.locked.toSet
    val maxSel  = Config.Loadout.maxSelected

    shapes.begin(ShapeType.Filled)
    towers.zipWithIndex.foreach { case (t, i) =>
      val r   = Ui.LoadoutRow(i)
      val isLocked = locked.contains(t.name)
      val isSel    = selected.contains(t.name)
      box(r, if isLocked then Config.PanelColor else Config.ButtonFill)
      if isSel then
        shapes.setColor(t.color)
        shapes.rect(r.x, r.y, r.w, 4f)
        shapes.rect(r.x, r.y + r.h - 4f, r.w, 4f)
        shapes.rect(r.x, r.y, 4f, r.h)
        shapes.rect(r.x + r.w - 4f, r.y, 4f, r.h)
    }
    button(Ui.LoadoutConfirm)
    button(Ui.LoadoutBack)
    shapes.end()

    batch.begin()
    centered("CHOOSE YOUR LOADOUT", Config.WorldW / 2, 560f, 3f)
    centered(s"slots ${selected.size} / $maxSel", Config.WorldW / 2, 520f, 1.4f, Config.DimText)

    towers.zipWithIndex.foreach { case (t, i) =>
      val r        = Ui.LoadoutRow(i)
      val isLocked = locked.contains(t.name)
      if isLocked then
        centered("?", r.centerX, r.centerY + 8f, 2.2f, Config.DimText)
      else
        text(s"${t.name}", r.x + 16f, r.centerY + 8f, 1.4f, t.color)
        text(s"${t.cost}g", r.x + r.w - 70f, r.centerY + 8f, 1.2f, Config.ShotColor)
        text(kindName(t.kind), r.x + 16f, r.y + 12f, 0.9f, Config.DimText)
        if selected.contains(t.name) then
          centered("SELECTED", r.centerX, r.y + 12f, 1.0f, Config.BuffTint)
    }

    buttonLabel(Ui.LoadoutConfirm, "CONFIRM")
    buttonLabel(Ui.LoadoutBack, "BACK")
    batch.end()
    if dev then drawDevOverlay()
    present()

  def drawRecap(
      won: Boolean,
      level: Int,
      wave: Int,
      lives: Int,
      gold: Int,
      newEntries: Vector[JournalEntry],
      knownEntries: Vector[JournalEntry],
      death: Option[DeathRecap],
      dev: Boolean = false
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
    if dev then drawDevOverlay()
    present()

  def drawGame(w: World, hover: Option[GridPos], fast: Boolean, selected: TowerSpec, notice: Option[String] = None, dev: Boolean = false): Unit =
    Config.setActiveBiome(w.run.biome)
    beginScene()
    drawScene(w, hover, fast, selected, notice)
    if dev then drawDevOverlay()
    present()

  /** The between-level draft: the offers on the table plus the reroll button and
    * the player's gold. No sim is stepped while this is on screen.
    */
  def drawDraft(w: World, dev: Boolean = false): Unit =
    Config.setActiveBiome(w.run.biome)
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
    if dev then drawDevOverlay()
    present()

  /** Frozen game view under a dim overlay plus the pause panel and its buttons. */
  def drawPaused(w: World, fast: Boolean, selected: TowerSpec, dev: Boolean = false): Unit =
    Config.setActiveBiome(w.run.biome)
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

    if dev then drawDevOverlay()
    present()

  /** Developer overlay: a panel of cheat buttons drawn on top of the live game.
    * Call it between the scene and [[present]] so it is captured by the bloom
    * composite. Pure drawing — no sim is touched here.
    */
  def drawDevOverlay(): Unit =
    val panel = Rect(Config.BoardPx, 330f, Config.PanelPx, 290f)
    shapes.begin(ShapeType.Filled)
    shapes.setColor(0f, 0f, 0f, 0.6f)
    shapes.rect(panel.x, panel.y, panel.w, panel.h)
    button(Ui.DevGold100)
    button(Ui.DevGold1000)
    button(Ui.DevMeta100)
    button(Ui.DevUnlockTowers)
    button(Ui.DevUnlockTalents)
    shapes.end()

    batch.begin()
    centered(Messages.t("dev.title"), panel.centerX, panel.y + panel.h - 18f, 1.8f, Config.ShotColor)
    buttonLabel(Ui.DevGold100, Messages.t("dev.gold100"))
    buttonLabel(Ui.DevGold1000, Messages.t("dev.gold1000"))
    buttonLabel(Ui.DevMeta100, Messages.t("dev.meta100"))
    buttonLabel(Ui.DevUnlockTowers, Messages.t("dev.unlockTowers"))
    buttonLabel(Ui.DevUnlockTalents, Messages.t("dev.unlockTalents"))
    batch.end()

  /** The in-game scene: board, actors, shots, overlays and the HUD. */
  private def drawScene(w: World, hover: Option[GridPos], fast: Boolean, selected: TowerSpec, notice: Option[String]): Unit =
    shapes.begin(ShapeType.Filled)
    drawBoard(w.level)
    drawTowers(w)
    drawEnemies(w)
    drawShots(w)
    drawSparks()
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

  /** The board is filled with the grid-line color, then cells are drawn inset
    * by one unit so the grid line shows through as the gap between them. The
    * field cells are lit by the ambient (global-illumination) term.
    */
  private def drawBoard(level: Int): Unit =
    val path = Grid.pathCells(level)
    shapes.setColor(Config.GridLine)
    shapes.rect(0f, 0f, Config.BoardPx, Config.BoardPx)
    for
      gx <- 0 until Config.GridSize
      gy <- 0 until Config.GridSize
    do
      val cell = GridPos(gx, gy)
      val base = if path.contains(cell) then Config.PathColor else Config.BuildColor
      shapes.setColor(lit(base))
      shapes.rect(gx * Config.CellPx + 1f, gy * Config.CellPx + 1f, Config.CellPx - 2f, Config.CellPx - 2f)

  private def drawTowers(w: World): Unit =
    w.towers.foreach { t =>
      // soft drop shadow: two fading circles offset under the tower
      val cx = t.cell.centerX + Config.ShadowOffsetX
      val cy = t.cell.centerY + Config.ShadowOffsetY
      val r  = Config.CellPx * 0.42f * Config.ShadowScale
      val sc = Config.ShadowColor
      shapes.setColor(sc.r, sc.g, sc.b, Config.ShadowAlpha * 0.5f)
      shapes.circle(cx, cy, r)
      shapes.setColor(sc.r, sc.g, sc.b, Config.ShadowAlpha)
      shapes.circle(cx, cy, r * 0.6f)
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

  /** Draws the live spark particles. Call within the Filled pass. */
  private def drawSparks(): Unit =
    val c = Config.SparkColor
    val s = Config.SparkSize
    sparks.foreach { p =>
      val a = math.min(1f, p.life / p.maxLife)
      shapes.setColor(c.r, c.g, c.b, a)
      shapes.rect(p.x - s / 2f, p.y - s / 2f, s, s)
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
    val towers = Config.LoadoutPool(w.run.loadout)
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
