package td

import com.badlogic.gdx.{ApplicationAdapter, Gdx, Input}
import com.badlogic.gdx.backends.lwjgl3.{Lwjgl3Application, Lwjgl3ApplicationConfiguration}

object Main:
  def main(args: Array[String]): Unit =
    val config = new Lwjgl3ApplicationConfiguration()
    config.setTitle("Tower Defense")
    config.setWindowedMode(Config.WorldW.toInt, Config.WorldH.toInt)
    config.setWindowSizeLimits(640, 480, -1, -1)
    config.useVsync(true)
    config.setForegroundFPS(60)
    new Lwjgl3Application(new TdGame, config)

/** Glue: reads input, advances the simulation, hands the state to the renderer.
  * All the rules live in [[Sim]]; this class only decides which screen is up.
  */
class TdGame extends ApplicationAdapter:
  private val NoticeTime = 2f

  private var gfx: Render      = scala.compiletime.uninitialized
  private var phase: Phase     = Phase.Menu
  private var fast: Boolean    = false
  private var selected: Int    = 0
  private var notice: Option[String] = None
  private var noticeTimer: Float     = 0f
  private var knownNames: Set[String]     = Set.empty
  private var startEntries: Vector[JournalEntry] = Vector.empty

  override def create(): Unit =
    gfx = new Render

  override def resize(width: Int, height: Int): Unit =
    gfx.resize(width, height)

  override def dispose(): Unit =
    gfx.dispose()

  override def render(): Unit =
    // Clamped so a stalled frame can't teleport enemies through the defence.
    val dt      = math.min(Gdx.graphics.getDeltaTime, Config.MaxStepDt)
    val clicked = Gdx.input.justTouched()
    val (mx, my) = gfx.toWorld(Gdx.input.getX, Gdx.input.getY)

    if notice.isDefined then
      noticeTimer -= dt
      if noticeTimer <= 0f then notice = None

    phase = phase match
      case Phase.Menu =>
        gfx.drawMenu()
        if clicked && Ui.Play.contains(mx, my) then newRun()
        else if Gdx.input.isKeyJustPressed(Input.Keys.T) then startTutorial()
        else Phase.Menu

      case Phase.Recap(won, level, wave, lives, gold, newEntries, knownEntries, death) =>
        gfx.drawRecap(won, level, wave, lives, gold, newEntries, knownEntries, death)
        if clicked && Ui.Retry.contains(mx, my) then newRun()
        else if clicked && Ui.Menu.contains(mx, my) then Phase.Menu
        else Phase.Recap(won, level, wave, lives, gold, newEntries, knownEntries, death)

      case Phase.Playing(w) =>
        // While paused nothing steps and only the pause keys / buttons act.
        if pausePressed then Phase.Paused(w)
        else
          if Gdx.input.isKeyJustPressed(Input.Keys.F) then fast = !fast

          if Gdx.input.isKeyJustPressed(Input.Keys.R) then
            if Config.reload() then notice = Some("config reloaded")
            else notice = Some("config reload failed")
            noticeTimer = NoticeTime

          if showCodesPressed then
            Messages.showCodes = !Messages.showCodes
            notice = Some(Messages.t("notice.showCodes", if Messages.showCodes then "on" else "off"))
            noticeTimer = NoticeTime

          Config.game.towers.indices.foreach { i =>
            if Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + i) then selected = i
          }
          selected = math.min(selected, Config.game.towers.size - 1)
          val sel   = Config.game.towers(selected)
          val hover = Some(Grid.cellAt(mx, my)).filter(Grid.inBounds)
          val cmds  = hover.filter(_ => clicked).map(Command.Build(_, sel.name)).toVector

          // Fast-forward runs extra whole steps rather than a bigger dt, so the
          // rules behave identically at any speed.
          val steps  = if fast then Config.FastForward else 1
          val first  = Sim.step(w, dt, cmds)
          val next   = (1 until steps).foldLeft(first)((acc, _) => Sim.step(acc, dt, Vector.empty))
          next.events.foreach(Journal.record)
          gfx.feedHits(next.hitFeedback)
          gfx.feedNames(next.nameEvents, knownNames)
          gfx.updateFx(dt)
          val drained = next.copy(events = Vector.empty, hitFeedback = Vector.empty, nameEvents = Vector.empty)

          gfx.drawGame(drained, hover, fast, sel, notice)

          if drained.won || drained.isOver then toRecap(drained)
          else Phase.Playing(drained)

      case Phase.Paused(w) =>
        val sel = Config.game.towers(math.min(selected, Config.game.towers.size - 1))
        gfx.drawPaused(w, fast, sel)

        if pausePressed then Phase.Playing(w)
        else if clicked && Ui.Resume.contains(mx, my) then Phase.Playing(w)
        else if clicked && Ui.Exit.contains(mx, my) then Phase.Menu
        else Phase.Paused(w)

  /** Every run starts from a clean World, so nothing leaks across retries. */
  private def newRun(): Phase =
    fast = false
    val w = Sim.initial(System.nanoTime())
    beginRun(w)
    Phase.Playing(w)

  private def startTutorial(): Phase =
    fast = false
    val w = Sim.initial(RunGen.tutorial(System.nanoTime()))
    beginRun(w)
    Phase.Playing(w)

  /** Snapshots the journal so the recap can tell NEW entries from known ones. */
  private def beginRun(w: World): Unit =
    Journal.recordRun(w.run)
    val data = Journal.load().getOrElse(JournalData.empty)
    startEntries = data.entries
    knownNames = data.entries.collect { case e if e.traitKey == "name" => e.enemy }.toSet
    gfx.clearFx()

  /** Run ended: build the recap (new vs known entries, plus a death summary). */
  private def toRecap(w: World): Phase =
    val after = Journal.load().getOrElse(JournalData.empty).entries
    val newEntries   = after.filterNot(startEntries.contains)
    val knownEntries = after.filter(startEntries.contains)
    val death        = if w.isOver then deathRecap(w) else None
    Phase.Recap(w.won, w.level, w.wave, w.lives, w.gold, newEntries, knownEntries, death)

  private def damageKinds: Vector[DamageKind] =
    Vector(DamageKind.Kinetic, DamageKind.Fire, DamageKind.Slow, DamageKind.Splash, DamageKind.Explosion)

  private def dominantKind(res: Resistances): Option[DamageKind] =
    damageKinds.maxBy(res(_)) match
      case k if res(k) > 0f => Some(k)
      case _                => None

  private def deathRecap(w: World): Option[DeathRecap] =
    if w.leaksByType.isEmpty then None
    else
      val total = w.leaksByType.values.sum
      val resisted = w.leaksByType.toVector.flatMap { case (tpe, n) =>
        val res = Config.enemySpec(tpe).resist.layered(w.run.resistTheme)
        dominantKind(res).map(k => k -> n)
      }
      if resisted.isEmpty then None
      else
        val byKind = resisted.groupBy(_._1).view.mapValues(_.map(_._2).sum).toMap
        val resistedKind = byKind.toVector.maxBy { case (k, n) => (n, k.ordinal) }._1
        val leakPct      = math.round(100f * byKind(resistedKind) / total).toInt
        val counterKind  = damageKinds.minBy(k => leakResistSum(w, k))
        val brought      = w.run.loadout.map(Config.towerSpec(_).kind).toSet ++ w.towers.map(_.spec.kind).toSet
        Some(DeathRecap(resistedKind, leakPct, counterKind, !brought.contains(counterKind)))

  private def leakResistSum(w: World, kind: DamageKind): Float =
    w.leaksByType.toVector.map { case (tpe, n) =>
      Config.enemySpec(tpe).resist.layered(w.run.resistTheme)(kind) * n
    }.sum

  /** ESC toggles pause; P is a redundant alias so the run never aborts by
    * accident. Abandoning a run now happens through the pause menu's Exit.
    */
  private def pausePressed: Boolean =
    Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.P)

  /** Alt+I toggles the debug code display (Messages.showCodes). */
  private def showCodesPressed: Boolean =
    (Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)) &&
      Gdx.input.isKeyJustPressed(Input.Keys.I)
