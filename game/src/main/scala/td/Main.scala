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

      case Phase.GameOver(level, wave) =>
        gfx.drawGameOver(level, wave)
        if clicked && Ui.Retry.contains(mx, my) then newRun()
        else if clicked && Ui.Menu.contains(mx, my) then Phase.Menu
        else Phase.GameOver(level, wave)

      case Phase.Victory(level, lives, gold) =>
        gfx.drawVictory(level, lives, gold)
        if clicked && Ui.PlayAgain.contains(mx, my) then newRun()
        else if clicked && Ui.Menu.contains(mx, my) then Phase.Menu
        else Phase.Victory(level, lives, gold)

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
          val drained = next.copy(events = Vector.empty)

          gfx.drawGame(drained, hover, fast, sel, notice)

          if drained.won then Phase.Victory(drained.level, drained.lives, drained.gold)
          else if drained.isOver then Phase.GameOver(drained.level, drained.wave)
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
    Journal.recordRun(w.run)
    Phase.Playing(w)

  private def startTutorial(): Phase =
    fast = false
    val w = Sim.initial(RunGen.tutorial(System.nanoTime()))
    Journal.recordRun(w.run)
    Phase.Playing(w)

  /** ESC toggles pause; P is a redundant alias so the run never aborts by
    * accident. Abandoning a run now happens through the pause menu's Exit.
    */
  private def pausePressed: Boolean =
    Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.P)

  /** Alt+I toggles the debug code display (Messages.showCodes). */
  private def showCodesPressed: Boolean =
    (Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT)) &&
      Gdx.input.isKeyJustPressed(Input.Keys.I)
