package td

import com.badlogic.gdx.{ApplicationAdapter, Gdx, Input}
import com.badlogic.gdx.backends.lwjgl3.{Lwjgl3Application, Lwjgl3ApplicationConfiguration}

object Main:
  def main(args: Array[String]): Unit =
    val config = new Lwjgl3ApplicationConfiguration()
    config.setTitle(Messages.t("game.title"))
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
  private var dev: Boolean                = false
  private var softPause: Boolean           = false

  /** Which cheat button a dev-overlay click landed on. */
  private sealed trait DevAction
  private case object Gold100       extends DevAction
  private case object Gold1000      extends DevAction
  private case object Meta100       extends DevAction
  private case object UnlockTowers  extends DevAction
  private case object UnlockTalents extends DevAction

  private def devButtonAt(mx: Float, my: Float): Option[DevAction] =
    if Ui.DevGold100.contains(mx, my) then Some(Gold100)
    else if Ui.DevGold1000.contains(mx, my) then Some(Gold1000)
    else if Ui.DevMeta100.contains(mx, my) then Some(Meta100)
    else if Ui.DevUnlockTowers.contains(mx, my) then Some(UnlockTowers)
    else if Ui.DevUnlockTalents.contains(mx, my) then Some(UnlockTalents)
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

  override def resize(width: Int, height: Int): Unit =
    gfx.resize(width, height)

  override def dispose(): Unit =
    gfx.dispose()

  override def render(): Unit =
    // Clamped so a stalled frame can't teleport enemies through the defence.
    val dt        = math.min(Gdx.graphics.getDeltaTime, Config.MaxStepDt)
    val rawClicked = Gdx.input.justTouched()
    val (mx, my) = gfx.toWorld(Gdx.input.getX, Gdx.input.getY)

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
        if clicked && Ui.Play.contains(mx, my) then newRun()
        else if clicked && Ui.Tutorial.contains(mx, my) then startTutorial()
        else if clicked && Ui.Talents.contains(mx, my) then Phase.Talents(Meta.active())
        else if clicked && Ui.Replays.contains(mx, my) then replayList(0)
        else if clicked && Ui.Progress.contains(mx, my) then progressView(0)
        else if Gdx.input.isKeyJustPressed(Input.Keys.T) then startTutorial()
        else Phase.Menu

      case Phase.Talents(data) =>
        // Refresh after UnlockTalents so newly granted nodes show immediately.
        val shown = if devAct.contains(UnlockTalents) then Meta.active() else data
        gfx.drawTalents(shown, dev)
        if clicked && Ui.TalentsBack.contains(mx, my) then Phase.Menu
        else if clicked then
          Ui.talentOrder.zipWithIndex.collectFirst {
            case (t, i) if Ui.TalentRow(i).contains(mx, my) => t
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
        if clicked && Ui.BriefingBegin.contains(mx, my) then Phase.Loadout(w.run, Config.Loadout.defaultSelected, Meta.active())
        else if clicked && Ui.BriefingBack.contains(mx, my) then Phase.Menu
        else if Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then Phase.Menu
        else Phase.Briefing(w)

      case Phase.Loadout(run, sel, meta) =>
        gfx.drawLoadout(run, sel, meta, dev)
        val unlocked = Meta.unlockedTowers(meta)
        def flash(msg: String): Unit =
          notice = Some(msg)
          noticeTimer = NoticeTime
        if clicked && Ui.LoadoutConfirm.contains(mx, my) then
          Phase.Playing(Sim.initial(run.copy(loadout = sel)))
        else if clicked && Ui.LoadoutBack.contains(mx, my) then Phase.Menu
        else if clicked then
          val clickedRow = Config.game.towers.indices.find(i => Ui.LoadoutRow(i).contains(mx, my))
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
        if clicked && Ui.Retry.contains(mx, my) then newRun()
        else if clicked && Ui.Menu.contains(mx, my) then Phase.Menu
        else Phase.Recap(won, level, wave, lives, gold, newEntries, knownEntries, death)

      case Phase.ProgressView(rows, page) =>
        gfx.drawProgress(rows, page, dev)
        if clicked && Ui.ReplayBack.contains(mx, my) then Phase.Menu
        else if clicked && Ui.ReplayPrev.contains(mx, my) && page > 0 then Phase.ProgressView(rows, page - 1)
        else if clicked && Ui.ReplayNext.contains(mx, my) && page < Ui.replayPages(rows.size) - 1 then
          Phase.ProgressView(rows, page + 1)
        else Phase.ProgressView(rows, page)

      case Phase.ReplayList(files, page) =>
        gfx.drawReplays(files, page, dev)
        val rowAt = (0 until Ui.ReplaysPerPage).find { i =>
          Ui.ReplayRow(i).contains(mx, my) && page * Ui.ReplaysPerPage + i < files.size
        }
        if clicked && Ui.ReplayBack.contains(mx, my) then Phase.Menu
        else if clicked && Ui.ReplayPrev.contains(mx, my) && page > 0 then Phase.ReplayList(files, page - 1)
        else if clicked && Ui.ReplayNext.contains(mx, my) && page < Ui.replayPages(files.size) - 1 then
          Phase.ReplayList(files, page + 1)
        else if clicked && rowAt.isDefined then startReplay(files, page, files(page * Ui.ReplaysPerPage + rowAt.get)._2)
        else Phase.ReplayList(files, page)

      case Phase.Replaying(pb0) =>
        if Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) then
          Config.reload()
          replayList(0)
        else
          val pb1 =
            if Gdx.input.isKeyJustPressed(Input.Keys.SPACE) then pb0.copy(paused = !pb0.paused)
            else pb0
          val pb2 =
            if Gdx.input.isKeyJustPressed(Input.Keys.RIGHT) then pb1.copy(speed = stepSpeed(pb1.speed, 1))
            else if Gdx.input.isKeyJustPressed(Input.Keys.LEFT) then pb1.copy(speed = stepSpeed(pb1.speed, -1))
            else pb1
          val (pb3, hits, names) = pb2.advance(dt)
          gfx.feedHits(hits)
          gfx.feedNames(names, knownNames)
          val hud    = Messages.t("replay.hud", s"${pb3.speed}x")
          val status =
            if pb3.finished then Messages.t("replay.finished")
            else if pb3.paused then Messages.t("replay.paused")
            else ""
          val line = if status.isEmpty then hud else s"$hud  $status"
          if pb3.world.draft.isDefined then gfx.drawDraft(pb3.world, dev)
          else gfx.drawGame(pb3.world, None, false, Config.game.towers.head, Some(line), dev)
          Phase.Replaying(pb3)

      case Phase.Playing(w) =>
        // The board (size/route/colors) is biome-specific, so resolve the active
        // biome before any grid math (hover, build validity) or the sim step.
        Config.setActive(w.run.biome, w.level)
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
                  if Ui.DraftReroll.contains(mx, my) &&
                      Meta.effects(w.run.talents).rerollUnlocked && w.gold >= d.rerollCost then
                    Vector(Command.Reroll())
                  else
                    d.offers.zipWithIndex.collectFirst {
                      case (DraftOffer.Relic(id), i) if Ui.DraftOffer(i).contains(mx, my)   => Command.PickRelic(id)
                      case (DraftOffer.Augment(id), i) if Ui.DraftOffer(i).contains(mx, my) => Command.PickAugment(id)
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
                if Gdx.input.isKeyJustPressed(Input.Keys.NUM_1 + i) then selected = i
              }
              selected = math.min(selected, pool.size - 1)
              val sel   = pool(selected)
              val hover = if devClicked then None else Some(Grid.cellAt(mx, my)).filter(Grid.inBounds)
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
        else if clicked && Ui.Resume.contains(mx, my) then Phase.Playing(w)
        else if clicked && Ui.Exit.contains(mx, my) then Phase.Menu
        else Phase.Paused(w)

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

  private def stepSpeed(speed: Double, dir: Int): Double =
    val i = Playback.Speeds.indexWhere(_ == speed) match
      case -1 => Playback.Speeds.indexOf(1.0)
      case k  => k
    Playback.Speeds(math.max(0, math.min(Playback.Speeds.size - 1, i + dir)))

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
    Config.setActive(w.run.biome, w.level)
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
