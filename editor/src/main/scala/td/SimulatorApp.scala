package td

import java.awt.{BorderLayout, Dimension}
import java.awt.event.{WindowAdapter, WindowEvent}
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.DefaultTableModel

/** The simulator window: set up a point in a player's progress (start level,
  * unlocked towers, talents, draft picks, lives already lost), play many games
  * from there with a bot, and save the setup as a file that
  * `scripts/sim.sh setup <file>` runs too.
  *
  * Each run reloads the game config, so saving in the editor and pressing Run
  * again simulates the change.
  */
object SimulatorApp:
  def main(args: Array[String]): Unit =
    SwingUtilities.invokeLater { () =>
      UIManager.getInstalledLookAndFeels.find(_.getName == "Nimbus").foreach(l => UIManager.setLookAndFeel(l.getClassName))
      UIManager.put("Slider.paintValue", false)
      val f = new SimulatorFrame(args.headOption.map(File(_)))
      f.setVisible(true)
      sys.env.get("TD_SIM_SHOTS").foreach(dir => f.screenshot(File(dir)))
    }

final class SimulatorFrame(start: Option[File]) extends JFrame:
  private var setup: SimSetup = SimSetup()
  private var file: Option[File] = None
  private var dirty = false
  @volatile private var stopping = false
  private var running = false

  private val form     = JPanel(BorderLayout())
  private val status   = JLabel(" ")
  private val progress = JProgressBar()
  private val results  = new DefaultTableModel(Array[AnyRef]("Loadout", "Games", "Won", "Lives lost (won)",
    "Gold left (won)", "Gold spent", "Towers", "Built (per game)"), 0):
    override def isCellEditable(r: Int, c: Int): Boolean = false
  private val table    = JTable(results)
  private val runButton  = Controls.button("Run", "Play the games")(run())
  private val stopButton = Controls.button("Stop", "Stop after the current game") { stopping = true }
  private val command  = JTextField()

  setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
  setSize(1400, 900)
  setLocationRelativeTo(null)
  stopButton.setEnabled(false)
  command.setEditable(false)
  progress.setStringPainted(true)
  table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN)
  table.getColumnModel.getColumn(0).setPreferredWidth(260)
  table.getColumnModel.getColumn(7).setPreferredWidth(260)

  private val bar = Controls.row(
    Controls.button("New", "Start a new setup")(if confirmLoss() then load(SimSetup(), None)),
    Controls.button("Open…", "Open a setup file")(open()),
    Controls.button("Save", "Save the setup")(save(file)),
    Controls.button("Save as…", "Save the setup to a new file")(save(None)),
    JLabel("   "), status)

  private val right = JPanel(BorderLayout())
  right.add(Controls.row(runButton, stopButton, progress), BorderLayout.NORTH)
  right.add(JScrollPane(table), BorderLayout.CENTER)
  right.add(Controls.row(JLabel("Same run from a terminal:"), command), BorderLayout.SOUTH)
  command.setColumns(60)

  private val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, form, right)
  split.setDividerLocation(560)
  getContentPane.add(bar, BorderLayout.NORTH)
  getContentPane.add(split, BorderLayout.CENTER)
  addWindowListener(new WindowAdapter:
    override def windowClosing(e: WindowEvent): Unit =
      if confirmLoss() then
        stopping = true
        dispose())

  start.fold(load(SimSetup(), None)) { f =>
    SimSetup.load(f.getPath).fold(err => { message(err); load(SimSetup(), None) }, s => load(s, Some(f)))
  }

  // -- setup state -------------------------------------------------------------------

  private def load(s: SimSetup, from: Option[File]): Unit =
    setup = s
    file = from
    dirty = false
    showForm()
    refresh()

  private def change(f: SimSetup => SimSetup, rebuild: Boolean = false): Unit =
    setup = f(setup)
    dirty = true
    if rebuild then showForm()
    refresh()

  private def refresh(): Unit =
    setTitle(s"${if dirty then "* " else ""}Square Defense simulator — ${file.fold("new setup")(_.getAbsolutePath)}")
    status.setText(if dirty then "unsaved changes" else file.fold("not saved")(f => s"saved: ${f.getName}"))
    command.setText(file.fold("save the setup to get a command")(f =>
      s"scripts/sim.sh setup ${relative(f)}"))

  private def relative(f: File): String =
    val here = File(".").getCanonicalFile.toPath
    val p    = f.getCanonicalFile.toPath
    if p.startsWith(here) then here.relativize(p).toString else p.toString

  private def confirmLoss(): Boolean =
    !dirty || JOptionPane.showConfirmDialog(this, "Throw away the unsaved setup?", "Simulator",
      JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION

  private def message(text: String): Unit =
    JOptionPane.showMessageDialog(this, text, "Simulator", JOptionPane.WARNING_MESSAGE)

  private def chooser(): JFileChooser =
    val dir = file.flatMap(f => Option(f.getAbsoluteFile.getParentFile)).getOrElse(File(SimSetup.DefaultDir))
    dir.mkdirs()
    val c = JFileChooser(dir)
    c.setFileFilter(FileNameExtensionFilter("Simulation setup (*.json)", "json"))
    c

  private def open(): Unit =
    if confirmLoss() then
      val c = chooser()
      if c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION then
        SimSetup.load(c.getSelectedFile.getPath).fold(message, s => load(s, Some(c.getSelectedFile)))

  private def save(to: Option[File]): Unit =
    val target = to.orElse {
      val c = chooser()
      c.setSelectedFile(File(c.getCurrentDirectory, s"${if setup.name.nonEmpty then setup.name.toLowerCase.replaceAll("[^a-z0-9]+", "-") else "setup"}.json"))
      Option.when(c.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)(c.getSelectedFile)
        .map(f => if f.getName.endsWith(".json") then f else File(f.getPath + ".json"))
    }
    target.foreach { f =>
      SimSetup.save(f.getPath, setup).fold(message, _ =>
        file = Some(f)
        dirty = false
        refresh())
    }

  /** The config the setup plays, for the lists in the form. */
  private def config: Option[GameConfig] =
    SimSetup.loadConfig(setup).fold(err => { status.setText(s"config: $err"); None }, Some(_))

  // -- form --------------------------------------------------------------------------

  private def showForm(): Unit =
    form.removeAll()
    val f = Form()
    f.section("Setup")
    f.field("Name", Controls.text(setup.name, n => change(_.copy(name = n.trim))))
    val configLabel = JLabel(if setup.config.isEmpty then
      (if File(ConfigLoader.FileName).isFile then "./config.json (the editor's)" else "built-in") else setup.config)
    f.field("Game config", Controls.row(configLabel,
      Controls.button("Choose…", "Play a different config file")(chooseConfig()),
      Controls.button("Default", "./config.json if there is one, else the built-in config")(
        change(_.copy(config = ""), rebuild = true))),
      "Reloaded on every run, so changes saved in the editor are picked up")

    config.foreach { cfg =>
      Config.use(cfg)
      f.section("Where")
      f.field("Bot", Controls.dropdown(SimSetup.Bots, setup.bot, b => change(_.copy(bot = b)), {
        case "builder" => "builder: uses every tower it may, best damage per gold"
        case "spender" => "spender: only the cheapest tower"
        case "naive"   => "naive: cheapest tower, first free cell"
        case other     => other
      }))
      val biomes = SimSetup.Rolled +: cfg.biomes.filter(b => !b.starter && b.id != "tutorial").map(_.id)
      f.field("Biome", Controls.dropdown(biomes, setup.biome, b => change(_.copy(biome = b)), b =>
        if b == SimSetup.Rolled then "rolled like the game (random biome and twist)"
        else cfg.biomes.find(_.id == b).fold(b)(_.name)), "Level 1 is always played in the starter biome")
      val levels = cfg.levels.size
      f.field("Start at level", Controls.stepper(1, math.max(1, levels), math.min(setup.level, levels), v =>
        change(_.copy(level = v), rebuild = true)), "The level the games are played on; earlier levels count as cleared")
      f.field("Lives already lost", Controls.stepper(0, 20, setup.livesLost, v => change(_.copy(livesLost = v))),
        "Lives carry over between levels; start this many down")

      f.section("Unlocked towers")
      cfg.towers.foreach { t =>
        val starting = cfg.loadout.defaultSelected.contains(t.name)
        val needs    = cfg.loadout.levelsToUnlock(t.name)
        f.full(Controls.checkbox(setup.unlocked.contains(t.name), on =>
          change(x => x.copy(unlocked = if on then (x.unlocked :+ t.name).distinct else x.unlocked.filterNot(_ == t.name)),
            rebuild = true),
          s"${t.name}  (${t.cost} gold${if starting then ", a new player's tower" else ""}" +
            s"${if needs > 0 then s", unlocks after level $needs" else ""})"))
      }
      f.full(Controls.checkbox(setup.everyLoadout, v => change(_.copy(everyLoadout = v)),
        s"when more than ${cfg.loadout.maxSelected} are unlocked, try every loadout of ${cfg.loadout.maxSelected} (else bring all)"))
      if setup.unlocked.isEmpty then f.full(JLabel(s"none ticked: the run brings ${cfg.loadout.defaultSelected.mkString(", ")}"))
      SimSetup.warnings(cfg, setup).foreach(w => f.full(JLabel(s"<html><font color='#B00020'>$w</font></html>")))

      f.section("Talents owned  (prerequisites are added automatically)")
      TalentLayer.values.foreach { layer =>
        cfg.talents.filter(_.layer == layer).foreach { t =>
          f.full(Controls.checkbox(setup.talents.contains(t.id), on =>
            change(x => x.copy(talents = if on then (x.talents :+ t.id).distinct else x.talents.filterNot(_ == t.id))),
            s"${t.name}  (${layer.toString.toLowerCase}: ${TalentsTab.describe(t)})"))
        }
      }

      f.section("Draft picks so far")
      cfg.relics.foreach { r =>
        f.full(Controls.checkbox(setup.relics.contains(r.id), on =>
          change(x => x.copy(relics = if on then (x.relics :+ r.id).distinct else x.relics.filterNot(_ == r.id))),
          s"relic: ${r.name}"))
      }
      f.field("Augment waiting", Controls.dropdown(None +: cfg.augments.map(a => Some(a.id)), setup.augment,
        a => change(_.copy(augment = a)), _.fold("none")(id => cfg.augments.find(_.id == id).fold(id)(_.name))),
        "An augment picked in the last draft, socketed on the first tower built")

      f.section("Games")
      f.field("Games per loadout", Controls.slider(1, 1000, 1, setup.games, v => change(_.copy(games = v.toInt)), log = true))
      f.field("First seed", Controls.stepper(1, Int.MaxValue, setup.seed.toInt, v => change(_.copy(seed = v.toLong))),
        "Game i uses seed + i; the same seeds always play the same games")
      f.field("Bot thinks every", Controls.stepper(1, 50, setup.think, v => change(_.copy(think = v))), "Simulation steps")
    }
    val scroll = JScrollPane(f.finish())
    scroll.getVerticalScrollBar.setUnitIncrement(24)
    form.add(scroll, BorderLayout.CENTER)
    form.revalidate()
    form.repaint()

  private def chooseConfig(): Unit =
    val c = JFileChooser(File(".").getAbsoluteFile)
    c.setFileFilter(FileNameExtensionFilter("Game config (*.json)", "json"))
    if c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION then
      change(_.copy(config = relative(c.getSelectedFile)), rebuild = true)

  // -- running -----------------------------------------------------------------------

  private def run(): Unit =
    if !running then
      SimSetup.loadConfig(setup) match
        case Left(err) => message(s"config: $err")
        case Right(cfg) =>
          Config.use(cfg)
          SimSetup.problems(cfg, setup) match
            case ps if ps.nonEmpty => message(ps.mkString("\n"))
            case _ =>
              val s = setup
              results.setRowCount(0)
              running = true
              stopping = false
              runButton.setEnabled(false)
              stopButton.setEnabled(true)
              progress.setValue(0)
              val began = System.nanoTime()
              val work: Runnable = () =>
                try
                  SimSetup.run(cfg, s,
                    progress = (done, total) => SwingUtilities.invokeLater { () =>
                      progress.setMaximum(total)
                      progress.setValue(done)
                      progress.setString(s"$done / $total games")
                    },
                    result = r => SwingUtilities.invokeLater(() => addRow(r)),
                    stop = () => stopping)
                  ()
                catch case e: Exception => SwingUtilities.invokeLater(() => message(s"run failed: ${e.getMessage}"))
                finally
                  val secs = (System.nanoTime() - began) / 1e9
                  SwingUtilities.invokeLater { () =>
                    running = false
                    runButton.setEnabled(true)
                    stopButton.setEnabled(false)
                    progress.setString(f"${if stopping then "stopped" else "done"} in $secs%.0f s")
                  }
              val worker = new Thread(work, "simulator")
              worker.setDaemon(true)
              worker.start()

  private def addRow(r: SimSetup.Result): Unit =
    val st = r.stats
    def f1(x: Double) = Stats.fmt(x)
    results.addRow(Array[AnyRef](r.loadout.mkString(" + "), st.games.toString,
      f"${st.wins} (${100 * st.winRate}%.0f%%)", f1(st.livesLostWon), f1(st.goldLeftWon), f1(st.goldSpent),
      f1(st.towers), st.mix))

  /** For checking the layout without a screen: run a short simulation, save a
    * picture of the window and quit.
    */
  def screenshot(dir: File): Unit =
    dir.mkdirs()
    load(SimSetup(name = "screenshot", level = 2, games = 3, biome = "cube-farm",
      unlocked = Vector("Cuboid", "Matchbox", "Ice Cube", "Hole Punch"), talents = Vector("filler-rate")), None)
    run()
    val timer = new Timer(500, null)
    timer.addActionListener { _ =>
      if !running then
        timer.stop()
        val root = getRootPane
        val img  = java.awt.image.BufferedImage(root.getWidth, root.getHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
        root.paint(img.getGraphics)
        val out = File(dir, "simulator.png")
        javax.imageio.ImageIO.write(img, "png", out)
        println(s"simulator shot: ${out.getAbsolutePath}")
        dispose()
        sys.exit(0)
    }
    timer.start()
