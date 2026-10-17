package td

import com.badlogic.gdx.{ApplicationAdapter, Gdx, Input}
import com.badlogic.gdx.backends.lwjgl3.{Lwjgl3Application, Lwjgl3ApplicationConfiguration}
import com.badlogic.gdx.graphics.glutils.HdpiMode

object Main:
  def main(args: Array[String]): Unit =
    DataDir.forPackagedBuild()
    val config = new Lwjgl3ApplicationConfiguration()
    config.setTitle(Messages.t("game.title"))
    // Work in real pixels on high-DPI screens so text is rasterized at full resolution.
    config.setHdpiMode(HdpiMode.Pixels)
    // The game has no sound; skipping OpenAL avoids failures on machines without an audio device.
    config.disableAudio(true)
    Display.configure(config, DisplaySettings.load())
    // TD_WINDOW=WxH forces a window size (screenshot tours).
    sys.env.get("TD_WINDOW").map(_.split("x").map(_.toInt)) match
      case Some(Array(w, h)) => config.setWindowedMode(w, h)
      case _                 => ()
    config.setWindowSizeLimits(800, 450, -1, -1)
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
  private var dev: Boolean                = false
  private var softPause: Boolean           = false

  // Screenshot tour (TD_TOUR=<dir>): the remaining stops and frames left on this one.
  private var tour: Vector[Tour.Step] = Vector.empty
  private var tourDir: String         = ""
  private var tourFrames: Int         = 0

  /** Which cheat button a dev-overlay click landed on. */
  private sealed trait DevAction
  private case object Gold100       extends DevAction
  private case object Gold1000      extends DevAction
  private case object Meta100       extends DevAction
  private case object UnlockTowers  extends DevAction
  private case object UnlockTalents extends DevAction

  private def devButtonAt(mx: Float, my: Float): Option[DevAction] =
    import Layout.DevItem
    def at(item: DevItem) = Layout.devButton(item).contains(mx, my)
    if at(DevItem.Gold100) then Some(Gold100)
    else if at(DevItem.Gold1000) then Some(Gold1000)
    else if at(DevItem.Meta100) then Some(Meta100)
    else if at(DevItem.UnlockTowers) then Some(UnlockTowers)
    else if at(DevItem.UnlockTalents) then Some(UnlockTalents)
    else None

  /** Adds meta currency to the persistent balance (used by the dev overlay). */
  private def grantMeta(amount: Int): Unit =
    val data = Meta.load().getOrElse(MetaData.empty)
    Meta.save(data.copy(balance = data.balance + amount))

  /** Grants every talent, bypassing cost/prereqs (used by the dev overlay). */
  private def grantAllTalents(): Unit =
    val data    = Meta.load().getOrElse(MetaData.empty)
    val missing = Config.talents.map(_.id).filterNot(data.talents.contains)
    Meta.save(data.copy(talents = data.talents ++ missing))

  override def create(): Unit =
    gfx = new Render
    sys.env.get("TD_TOUR").foreach { dir =>
      tourDir = dir
      tour = Tour.steps
      startTourStep()
    }

  private def startTourStep(): Unit =
    tour.headOption.foreach { s =>
      gfx.clearFx()
      s.action()
      phase = s.phase()
      tourFrames = s.frames
    }

  /** After a frame is drawn: F12 saves it; on a tour, the last frame of each stop is saved. */
  private def afterFrame(): Unit =
    if Gdx.input.isKeyJustPressed(Input.Keys.F12) then
      notice = Some(s"saved ${Shots.save(Shots.timestamped())}")
      noticeTimer = NoticeTime
    if tour.nonEmpty then
      tourFrames -= 1
      if tourFrames <= 0 then
        val path = Shots.save(s"$tourDir/${tour.head.name}.png")
        println(s"tour: $path")
        tour = tour.tail
        if tour.isEmpty then Gdx.app.exit() else startTourStep()

  override def resize(width: Int, height: Int): Unit =
    gfx.resize(width, height)

  override def dispose(): Unit =
    gfx.dispose()

  override def render(): Unit =
    // Clamped so a stalled frame can't teleport enemies through the defence.
    val dt        = math.min(Gdx.graphics.getDeltaTime, Config.MaxStepDt)
    val rawClicked = Gdx.input.justTouched()
    val (mx, my) = gfx.toUi(Gdx.input.getX, Gdx.input.getY)
    gfx.mouse = (mx, my)

    if notice.isDefined then
      noticeTimer -= dt
      if noticeTimer <= 0f then notice = None

    // Age FX (shake, sparks, floating labels) every frame so a shake that
    // fires on the final life loss still decays once we leave Playing.
    gfx.updateFx(dt)

    // Dev overlay toggle works on every screen, not just in-game.
    if Gdx.input.isKeyJustPressed(Input.Keys.GRAVE) then
      dev = !dev
      notice = Some(Messages.t(if dev then "dev.on" else "dev.off"))
      noticeTimer = NoticeTime

    // Dev-bar clicks: meta cheats apply on any screen; gold/towers become
    // commands only while Playing. Always swallow the click so it can't hit UI under the bar.
    val devAct = if dev && rawClicked then devButtonAt(mx, my) else None
    val devCmd: Option[Command] = devAct.flatMap {
      case Gold100       => Some(Command.GrantGold(100))
      case Gold1000      => Some(Command.GrantGold(1000))
      case UnlockTowers  => Some(Command.UnlockTowers())
      case Meta100       => grantMeta(100); None
      case UnlockTalents => grantAllTalents(); None
    }
    val devClicked = devAct.isDefined
    val clicked    = rawClicked && !devClicked

    phase = phase match
      case Phase.Menu =>
        gfx.drawMenu(dev)
        import Layout.MenuItem
        val item = if clicked then MenuItem.values.find(i => Layout.menuButton(i).contains(mx, my)) else None
        item match
          case Some(MenuItem.Play)     => newRun()
          case Some(MenuItem.Tutorial) => startTutorial()
          case Some(MenuItem.Talents)  => Phase.Talents(Meta.active())
          case Some(MenuItem.Replays)  => replayList(0)
          case Some(MenuItem.Progress) => progressView(0)
          case Some(MenuItem.Settings) => Phase.Settings(DisplaySettings.load(), Phase.Menu)
          case Some(MenuItem.Quit)     => Gdx.app.exit(); Phase.Menu
          case None =>
            if Gdx.input.isKeyJustPressed(Input.Keys.T) then startTutorial() else Phase.Menu

      case Phase.Settings(draft, back) =>
        val fixed = draft.mode == WindowMode.Borderless
        gfx.drawSettings(draft, currentDisplay, fixed, dev)
        def resolution(dir: Int) =
          val (w, h) = DisplaySettings.cycle(Display.resolutions(draft.mode), (draft.width, draft.height), dir)
          draft.copy(width = w, height = h)
        def mode(dir: Int) =
          val all  = WindowMode.values
          val next = all((draft.mode.ordinal + dir + all.length) % all.length)
          // keep the size if the new mode offers it, else move to the closest one it does
          val (w, h) = DisplaySettings.cycle(Display.resolutions(next), (draft.width, draft.height), 0)
          draft.copy(mode = next, width = w, height = h)
        if clicked && Layout.settingsPrev(0).contains(mx, my) then Phase.Settings(mode(-1), back)
        else if clicked && Layout.settingsNext(0).contains(mx, my) then Phase.Settings(mode(1), back)
        else if clicked && !fixed && Layout.settingsPrev(1).contains(mx, my) then Phase.Settings(resolution(-1), back)
        else if clicked && !fixed && Layout.settingsNext(1).contains(mx, my) then Phase.Settings(resolution(1), back)
        else if clicked && Layout.settingsApply.contains(mx, my) then
          DisplaySettings.save(draft)
          Display.apply(draft)
          notice = Some(Messages.t("settings.applied", Messages.t(s"settings.mode.${draft.mode.toString.toLowerCase}")))
          noticeTimer = NoticeTime
          Phase.Settings(draft, back)
        else if (clicked && Layout.settingsBack.contains(mx, my)) || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then back
        else Phase.Settings(draft, back)

      case Phase.Talents(data) =>
        // Refresh after UnlockTalents so newly granted nodes show immediately.
        val shown = if devAct.contains(UnlockTalents) then Meta.active() else data
        gfx.drawTalents(shown, dev)
        if clicked && Layout.talentsBack.contains(mx, my) then Phase.Menu
        else if Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then Phase.Menu
        else if clicked then
          Layout.talentOrder.zipWithIndex.collectFirst {
            case (t, i) if Layout.talentRow(i).contains(mx, my) => t
          } match
            case Some(t) =>
              Meta.purchase(shown, t.id) match
                case Right(next) =>
                  Meta.save(next)
                  Phase.Talents(next)
                case Left(err) =>
                  notice = Some(err)
                  noticeTimer = NoticeTime
                  Phase.Talents(shown)
            case None => Phase.Talents(shown)
        else Phase.Talents(shown)

      case Phase.Briefing(w) =>
        gfx.drawBriefing(w, dev)
        if clicked && Layout.briefingBegin.contains(mx, my) then Phase.Loadout(w.run, Config.Loadout.defaultSelected, Meta.active())
        else if clicked && Layout.briefingBack.contains(mx, my) then Phase.Menu
        else if Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then Phase.Menu
        else Phase.Briefing(w)

      case Phase.Loadout(run, sel, meta) =>
        gfx.drawLoadout(run, sel, meta, dev)
        val unlocked = Meta.unlockedTowers(meta)
        def flash(msg: String): Unit =
          notice = Some(msg)
          noticeTimer = NoticeTime
        if clicked && Layout.loadoutConfirm.contains(mx, my) && sel.nonEmpty then
          Phase.Playing(Sim.initial(run.copy(loadout = sel)))
        else if (clicked && Layout.loadoutBack.contains(mx, my)) || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then
          Phase.Menu
        else if clicked then
          val clickedRow = Config.game.towers.indices.find(i => Layout.loadoutRow(i).contains(mx, my))
          clickedRow match
            case Some(i) =>
              val t = Config.game.towers(i)
              if !unlocked.contains(t.name) then
                Meta.purchaseTower(meta, t.name) match
                  case Right(next) =>
                    Meta.save(next)
                    flash(s"${t.name} unlocked")
                    Phase.Loadout(run, sel, next)
                  case Left(err) =>
                    flash(err)
                    Phase.Loadout(run, sel, meta)
              else if sel.contains(t.name) then Phase.Loadout(run, sel.filter(_ != t.name), meta)
              else if sel.size < Config.Loadout.maxSelected then Phase.Loadout(run, sel :+ t.name, meta)
              else
                flash("loadout is full")
                Phase.Loadout(run, sel, meta)
            case None => Phase.Loadout(run, sel, meta)
        else Phase.Loadout(run, sel, meta)

      case Phase.Recap(won, level, wave, lives, gold, newEntries, knownEntries, death) =>
        gfx.drawRecap(won, level, wave, lives, gold, newEntries, knownEntries, death, dev)
        if clicked && Layout.recapRetry.contains(mx, my) then newRun()
        else if clicked && Layout.recapMenu.contains(mx, my) then Phase.Menu
        else Phase.Recap(won, level, wave, lives, gold, newEntries, knownEntries, death)

      case Phase.ProgressView(rows, page) =>
        gfx.drawProgress(rows, page, dev)
        if (clicked && Layout.listBack.contains(mx, my)) || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then Phase.Menu
        else if clicked && Layout.listPrev.contains(mx, my) && page > 0 then Phase.ProgressView(rows, page - 1)
        else if clicked && Layout.listNext.contains(mx, my) && page < Layout.listPages(rows.size) - 1 then
          Phase.ProgressView(rows, page + 1)
        else Phase.ProgressView(rows, page)

      case Phase.ReplayList(files, page) =>
        gfx.drawReplays(files, page, dev)
        val rowAt = (0 until Layout.ListPerPage).find { i =>
          Layout.listRow(i).contains(mx, my) && page * Layout.ListPerPage + i < files.size
        }
        if (clicked && Layout.listBack.contains(mx, my)) || Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then Phase.Menu
        else if clicked && Layout.listPrev.contains(mx, my) && page > 0 then Phase.ReplayList(files, page - 1)
        else if clicked && Layout.listNext.contains(mx, my) && page < Layout.listPages(files.size) - 1 then
          Phase.ReplayList(files, page + 1)
        else if clicked && rowAt.isDefined then startReplay(files, page, files(page * Layout.ListPerPage + rowAt.get)._2)
        else Phase.ReplayList(files, page)

      case Phase.Replaying(pb0) =>
        def key(k: Int) = Gdx.input.isKeyJustPressed(k)
        def hit(r: Rect) = clicked && r.contains(mx, my)
        if key(Input.Keys.ESCAPE) || hit(Layout.replayExit) then
          Config.reload()
          replayList(0)
        else
          val toggled =
            if key(Input.Keys.SPACE) || hit(Layout.replayPause) then
              // play from the start again once the recording has finished
              if pb0.finished then pb0.seek(0).copy(paused = false) else pb0.copy(paused = !pb0.paused)
            else pb0
          val sped =
            if key(Input.Keys.RIGHT) || key(Input.Keys.PLUS) || key(Input.Keys.EQUALS) ||
                key(Input.Keys.NUMPAD_ADD) || hit(Layout.replayFaster) then toggled.faster(1)
            else if key(Input.Keys.LEFT) || key(Input.Keys.MINUS) || key(Input.Keys.NUMPAD_SUBTRACT) ||
                hit(Layout.replaySlower) then toggled.faster(-1)
            else toggled
          val bar = Layout.replayProgress
          val sought =
            // the bar is thin; accept clicks a little above and below it
            if clicked && Rect(bar.x, bar.y - 12f, bar.w, bar.h + 24f).contains(mx, my) then
              gfx.clearFx()
              sped.seek(math.round((mx - bar.x) / bar.w * sped.replay.steps))
            else sped
          val (pb, hits, names) = sought.advance(dt)
          gfx.feedHits(hits)
          gfx.feedNames(names, knownNames)
          if pb.world.draft.isDefined then gfx.drawDraft(pb.world, dev)
          else gfx.drawGame(pb.world, None, false, Config.game.towers.head, notice, dev, replay = Some(pb))
          Phase.Replaying(pb)

      case Phase.Playing(w) =>
        // The board (size/route/colors) is biome-specific, so resolve the active
        // biome before any grid math (hover, build validity) or the sim step.
        Config.setActive(w.biome, w.level)
        // ESC opens the pause menu (Phase.Paused). P does a *soft* pause: the
        // sim freezes but the screen stays live and every action (build, draft,
        // dev cheats) still works.
        if Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then
          softPause = false
          Phase.Paused(w)
        else
          if Gdx.input.isKeyJustPressed(Input.Keys.P) then
            softPause = !softPause
            notice = Some(Messages.t(if softPause then "pause.on" else "pause.off"))
            noticeTimer = NoticeTime

          if Gdx.input.isKeyJustPressed(Input.Keys.F) then fast = !fast

          if Gdx.input.isKeyJustPressed(Input.Keys.R) then
            if Config.reload() then notice = Some("config reloaded")
            else notice = Some("config reload failed")
            noticeTimer = NoticeTime

          if showCodesPressed then
            Messages.showCodes = !Messages.showCodes
            notice = Some(Messages.t("notice.showCodes", if Messages.showCodes then "on" else "off"))
            noticeTimer = NoticeTime

          // Soft pause freezes time but still applies commands (build/draft/cheats).
          val stepDt = if softPause then 0f else dt

          w.draft match
            case Some(d) =>
              // Between levels: only draft commands apply; the sim never steps.
              val cmds =
                if devClicked then devCmd.toVector
                else if clicked then
                  if Layout.draftReroll.contains(mx, my) &&
                      Meta.effects(w.run.talents).rerollUnlocked && w.gold >= d.rerollCost then
                    Vector(Command.Reroll())
                  else
                    val n = d.offers.size
                    d.offers.zipWithIndex.collectFirst {
                      case (DraftOffer.Relic(id), i) if Layout.draftCard(i, n).contains(mx, my)   => Command.PickRelic(id)
                      case (DraftOffer.Augment(id), i) if Layout.draftCard(i, n).contains(mx, my) => Command.PickAugment(id)
                    }.toVector
                else Vector.empty
              val next = Sim.step(w, stepDt, cmds)
              if next.draft.isDefined then gfx.drawDraft(next, dev)
              else
                val psel = Config.LoadoutPool(w.run.loadout)(math.min(selected, Config.LoadoutPool(w.run.loadout).size - 1))
                gfx.drawGame(next, None, fast, psel, notice = None, dev = dev)
              if next.won || next.isOver then toRecap(next)
              else Phase.Playing(next)

            case None =>
              val pool = Config.LoadoutPool(w.run.loadout)
              pool.indices.foreach { i =>
                if i < 9 && Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + i) then selected = i
              }
              // clicking a tower in the side panel selects it
              val towerRow = pool.indices.find(i => Layout.hudTower(i, gfx.hudTowersTop).contains(mx, my))
              if clicked then towerRow.foreach(i => selected = i)
              selected = math.min(selected, pool.size - 1)
              val sel   = pool(selected)
              val onBoard = Layout.board.contains(mx, my)
              val hover =
                if devClicked || !onBoard then None
                else
                  val (sx, sy) = Layout.toSim(mx, my)
                  Some(Grid.cellAt(sx, sy)).filter(Grid.inBounds)
              val cmds  =
                if devClicked then devCmd.toVector
                else hover.filter(_ => clicked).map { cell =>
                  if w.pendingAugment.isDefined && Sim.canAttachAugment(w, cell) then Command.AttachAugment(cell)
                  else Command.Build(cell, sel.name)
                }.toVector

              // Fast-forward runs extra whole steps rather than a bigger dt, so the
              // rules behave identically at any speed. Soft pause keeps a single
              // zero-dt step so commands still apply.
              val steps  = if softPause then 1 else if fast then Config.FastForward else 1
              val first  = Sim.step(w, stepDt, cmds)
              val next   = (1 until steps).foldLeft(first)((acc, _) => Sim.step(acc, stepDt, Vector.empty))
              next.events.foreach(Journal.record)
              gfx.feedHits(next.hitFeedback)
              gfx.feedNames(next.nameEvents, knownNames)
              if next.lives < w.lives then gfx.shake()
              val drained = next.copy(events = Vector.empty, hitFeedback = Vector.empty, nameEvents = Vector.empty)

              gfx.drawGame(drained, hover, fast, sel, notice, dev)

              if drained.won || drained.isOver then toRecap(drained)
              else Phase.Playing(drained)

      case Phase.Paused(w) =>
        val pool = Config.LoadoutPool(w.run.loadout)
        val sel = pool(math.min(selected, pool.size - 1))
        gfx.drawPaused(w, fast, sel, dev)

        if pausePressed then Phase.Playing(w)
        else if clicked && Layout.pauseButton(0).contains(mx, my) then Phase.Playing(w)
        else if clicked && Layout.pauseButton(1).contains(mx, my) then Phase.Settings(DisplaySettings.load(), Phase.Paused(w))
        else if clicked && Layout.pauseButton(2).contains(mx, my) then Phase.Menu
        else Phase.Paused(w)

    afterFrame()

  /** What the window is right now, for the settings screen. */
  private def currentDisplay: String =
    val g = Gdx.graphics
    val kind =
      if g.isFullscreen then Messages.t("settings.mode.fullscreen")
      else if DisplaySettings.load().mode == WindowMode.Borderless then Messages.t("settings.mode.borderless")
      else Messages.t("settings.mode.windowed")
    s"$kind  ·  ${g.getBackBufferWidth} x ${g.getBackBufferHeight}"

  private def progressView(page: Int): Phase =
    val rows = ProgressLog.summarize(ProgressLog.DefaultPath, None, None).getOrElse(Vector.empty)
    Phase.ProgressView(rows, page)

  private def replayList(page: Int): Phase =
    Phase.ReplayList(ReplayStore.list(ReplayStore.DefaultDir), page)

  /** Plays a saved game on its own config snapshot. The snapshot is restored when
    * the player leaves the replay (ESC), so the rest of the game is untouched.
    */
  private def startReplay(files: Vector[(String, Replay)], page: Int, r: Replay): Phase =
    ReplayStore.loadConfig(ReplayStore.DefaultDir, r.config) match
      case Right(cfg) =>
        Config.use(cfg)
        Config.clearActiveBiome()
        Grid.reset()
        gfx.clearFx()
        Phase.Replaying(Playback.start(r))
      case Left(_) =>
        notice = Some(Messages.t("replay.configMissing"))
        noticeTimer = NoticeTime
        Phase.ReplayList(files, page)

  /** Every run starts from a clean World, so nothing leaks across retries. */
  private def newRun(): Phase =
    fast = false
    val run = RunGen.roll(System.nanoTime(), Meta.active().talents)
    beginRun(Sim.initial(run))
    Phase.Briefing(Sim.initial(run))

  private def startTutorial(): Phase =
    fast = false
    val w = Sim.initial(RunGen.tutorial(System.nanoTime(), Meta.active().talents))
    beginRun(w)
    Phase.Briefing(w)

  /** Snapshots the journal so the recap can tell NEW entries from known ones. */
  private def beginRun(w: World): Unit =
    Journal.recordRun(w.run)
    val data = Journal.load().getOrElse(JournalData.empty)
    startEntries = data.entries
    knownNames = data.entries.collect { case e if e.traitKey == "name" => e.enemy }.toSet
    gfx.clearFx()

  /** Run ended: build the recap (new vs known entries, plus a death summary). */
  private def toRecap(w: World): Phase =
    Config.setActive(w.biome, w.level)
    Meta.bankRun(w)
    val after = Journal.load().getOrElse(JournalData.empty).entries
    val newEntries   = after.filterNot(startEntries.contains)
    val knownEntries = after.filter(startEntries.contains)
    val death        = if w.isOver then deathRecap(w) else None
    Phase.Recap(w.won, w.level, w.wave, w.lives, w.gold, newEntries, knownEntries, death)

  private def damageKinds: Vector[DamageKind] =
    Vector(DamageKind.Kinetic, DamageKind.Fire, DamageKind.Slow, DamageKind.Splash, DamageKind.Explosion, DamageKind.Shock)

  private def dominantKind(res: Resistances): Option[DamageKind] =
    damageKinds.maxBy(res(_)) match
      case k if res(k) > 0f => Some(k)
      case _                => None

  private def deathRecap(w: World): Option[DeathRecap] =
    if w.leaksByType.isEmpty then None
    else
      val total = w.leaksByType.values.sum
      val resisted = w.leaksByType.toVector.flatMap { case (tpe, n) =>
        val res = Config.enemySpec(tpe).resist.layered(w.resistTheme)
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
      Config.enemySpec(tpe).resist.layered(w.resistTheme)(kind) * n
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
