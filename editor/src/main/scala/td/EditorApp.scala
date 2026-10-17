package td

import java.awt.{BasicStroke, BorderLayout, Color as AwtColor, Dimension, Font, Graphics, Graphics2D, GridLayout, Polygon,
  RenderingHints}
import java.awt.event.{WindowAdapter, WindowEvent}
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import td.ConfigEdits.LevelSet

/** The level / wave / enemy editor: a separate window, not part of the game.
  * Opens a config file, edits it with sliders, steppers and dropdowns, and saves
  * it back. The game reads `config.json` in its working directory at start and
  * again when R is pressed, so saving there and pressing R tries a change at once.
  *
  * Run with `sbt editor/run` (or scripts/editor.sh / scripts\editor.cmd).
  */
object EditorApp:
  def main(args: Array[String]): Unit =
    SwingUtilities.invokeLater { () =>
      // Nimbus looks the same on every OS; sliders show their value in the box beside them
      UIManager.getInstalledLookAndFeels.find(_.getName == "Nimbus").foreach(l => UIManager.setLookAndFeel(l.getClassName))
      UIManager.put("Slider.paintValue", false)
      val f = new EditorFrame(args.headOption.map(File(_)))
      f.setVisible(true)
      sys.env.get("TD_EDITOR_SHOTS").foreach(dir => f.screenshots(File(dir)))
    }

/** The config being edited, the file it came from, and whether it changed since. */
final class EditorState(initial: GameConfig, initialFile: Option[File]):
  private var current = initial
  var file: Option[File] = initialFile
  var dirty = false
  private var listeners = Vector.empty[() => Unit]

  def cfg: GameConfig = current
  def onChange(f: () => Unit): Unit = listeners :+= f

  def update(f: GameConfig => GameConfig): Unit =
    val next = f(current)
    if next != current then
      current = next
      dirty = true
      listeners.foreach(_())

  def replace(cfg: GameConfig, from: Option[File]): Unit =
    current = cfg
    file = from
    dirty = false
    listeners.foreach(_())

final class EditorFrame(start: Option[File]) extends JFrame:
  private val gameConfig = File(ConfigLoader.FileName)
  private val state: EditorState =
    val from = start.orElse(Option.when(gameConfig.isFile)(gameConfig))
    val cfg = from.fold(ConfigLoader.bundled())(f => ConfigLoader.load(f.getPath)) match
      case Right(c) => c
      case Left(err) =>
        JOptionPane.showMessageDialog(null, s"Could not read ${from.fold("the bundled config")(_.getPath)}:\n$err\n\nOpening the bundled config instead.",
          "Editor", JOptionPane.WARNING_MESSAGE)
        ConfigLoader.bundled().fold(e => throw RuntimeException(e), identity)
    EditorState(cfg, from)

  private val status = JLabel(" ")
  private val enemies = EnemiesTab(state)
  private val levels  = LevelsTab(state)
  private val tabs = JTabbedPane()

  setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
  setSize(1280, 860)
  setLocationRelativeTo(null)
  tabs.addTab("Enemies", enemies)
  tabs.addTab("Levels & waves", levels)

  private val bar = Controls.row(
    Controls.button("Open…", "Open a config file")(open()),
    Controls.button("Save", s"Save (to ${gameConfig.getName} in the game folder if this config has no file yet)")(save(None)),
    Controls.button("Save as…", "Save to a new file")(saveAs()),
    Controls.button("Revert", "Throw away unsaved changes")(revert()),
    JLabel("   "), status)
  getContentPane.add(bar, BorderLayout.NORTH)
  getContentPane.add(tabs, BorderLayout.CENTER)
  state.onChange(() => refreshTitle())
  refreshTitle()

  addWindowListener(new WindowAdapter:
    override def windowClosing(e: WindowEvent): Unit =
      if !state.dirty || confirm("Quit without saving your changes?") then dispose())

  private def refreshTitle(): Unit =
    val where = state.file.fold("bundled config (not saved yet)")(_.getAbsolutePath)
    setTitle(s"${if state.dirty then "* " else ""}Square Defense editor — $where")
    status.setText(
      if state.dirty then "unsaved changes"
      else state.file.fold("bundled config")(f => s"saved: ${f.getName}"))

  private def confirm(q: String): Boolean =
    JOptionPane.showConfirmDialog(this, q, "Editor", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION

  private def chooser(): JFileChooser =
    val c = JFileChooser(state.file.flatMap(f => Option(f.getAbsoluteFile.getParentFile)).getOrElse(File(".").getAbsoluteFile))
    c.setFileFilter(FileNameExtensionFilter("Config (*.json)", "json"))
    c

  private def open(): Unit =
    if !state.dirty || confirm("Open another file and lose your unsaved changes?") then
      val c = chooser()
      if c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION then
        ConfigLoader.load(c.getSelectedFile.getPath) match
          case Right(cfg) => reload(cfg, Some(c.getSelectedFile))
          case Left(err)  => JOptionPane.showMessageDialog(this, err, "Cannot open", JOptionPane.ERROR_MESSAGE)

  private def revert(): Unit =
    if state.dirty && confirm("Throw away your unsaved changes?") then
      state.file.fold(ConfigLoader.bundled())(f => ConfigLoader.load(f.getPath)) match
        case Right(cfg) => reload(cfg, state.file)
        case Left(err)  => JOptionPane.showMessageDialog(this, err, "Cannot revert", JOptionPane.ERROR_MESSAGE)

  private def reload(cfg: GameConfig, file: Option[File]): Unit =
    state.replace(cfg, file)
    enemies.rebuild()
    levels.rebuild()

  private def saveAs(): Unit =
    val c = chooser()
    c.setSelectedFile(state.file.getOrElse(gameConfig))
    if c.showSaveDialog(this) == JFileChooser.APPROVE_OPTION then
      val f = if c.getSelectedFile.getName.endsWith(".json") then c.getSelectedFile else File(c.getSelectedFile.getPath + ".json")
      save(Some(f))

  /** Checks the config with the game's own loader, then writes it. */
  private def save(to: Option[File]): Unit =
    val target = to.orElse(state.file).getOrElse(gameConfig)
    ConfigLoader.parse(ConfigWriter.toJson(state.cfg)) match
      case Left(err) =>
        JOptionPane.showMessageDialog(this, s"The game would not accept this config:\n$err", "Not saved",
          JOptionPane.ERROR_MESSAGE)
      case Right(_) =>
        ConfigWriter.write(state.cfg, target.getPath) match
          case Left(err) => JOptionPane.showMessageDialog(this, err, "Not saved", JOptionPane.ERROR_MESSAGE)
          case Right(_) =>
            state.file = Some(target)
            state.dirty = false
            refreshTitle()

  /** Saves a picture of each tab (for checking the layout without a screen). */
  def screenshots(dir: File): Unit =
    dir.mkdirs()
    val timer = new Timer(800, null)
    var step = 0
    timer.addActionListener { _ =>
      step match
        case 0 => tabs.setSelectedIndex(0)
        case 1 => shot(File(dir, "editor-enemies.png"))
        case 2 => tabs.setSelectedIndex(1)
        case 3 => shot(File(dir, "editor-levels.png"))
        case _ => timer.stop(); dispose(); sys.exit(0)
      step += 1
    }
    timer.start()

  private def shot(f: File): Unit =
    val root = getRootPane
    val img = java.awt.image.BufferedImage(root.getWidth, root.getHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
    root.paint(img.getGraphics)
    javax.imageio.ImageIO.write(img, "png", f)
    println(s"editor shot: ${f.getAbsolutePath}")

// -- enemies -----------------------------------------------------------------------

/** Draws an enemy type as the game does: its shape and color at its size. */
object EnemyArt:
  def paint(g: Graphics2D, e: EnemySpec, cx: Double, cy: Double, scale: Double, heading: Float = 0f): Unit =
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val pts = e.shape.corners(e.size, heading)
    // y-up in the game, y-down on screen
    val poly = Polygon(pts.map((x, _) => math.round(cx + x * scale).toInt).toArray,
      pts.map((_, y) => math.round(cy - y * scale).toInt).toArray, pts.size)
    g.setColor(Controls.toAwt(e.color))
    g.fillPolygon(poly)
    if e.boss then
      g.setColor(AwtColor(255, 230, 165))
      g.setStroke(BasicStroke(2f))
      g.drawPolygon(poly)

  def icon(e: EnemySpec, px: Int): Icon = new Icon:
    def getIconWidth: Int = px
    def getIconHeight: Int = px
    def paintIcon(c: java.awt.Component, g: Graphics, x: Int, y: Int): Unit =
      paint(g.asInstanceOf[Graphics2D], e, x + px / 2.0, y + px / 2.0, px * 0.8 / math.max(1f, e.size))

/** Shows an enemy on a 10x10-map tile and a 14x14-map tile, at the size the game draws it. */
final class EnemyPreview(enemy: () => Option[EnemySpec]) extends JComponent:
  setPreferredSize(Dimension(300, 150))
  override def paintComponent(g0: Graphics): Unit =
    val g = g0.asInstanceOf[Graphics2D]
    g.setColor(AwtColor(2, 26, 1))
    g.fillRect(0, 0, getWidth, getHeight)
    enemy().foreach { e =>
      Vector((10, 20), (14, 160)).foreach { (grid, x) =>
        val tile = 110
        val cellPx = Config.game.board.boardPx / grid
        g.setColor(AwtColor(50, 138, 46))
        g.fillRect(x, 12, tile, tile)
        EnemyArt.paint(g, e, x + tile / 2.0, 12 + tile / 2.0, tile / cellPx)
        g.setColor(AwtColor(255, 230, 165))
        g.drawString(s"on a ${grid}x$grid map", x + 4, 12 + tile + 16)
      }
    }

final class EnemiesTab(state: EditorState) extends JPanel(BorderLayout()):
  private val model = DefaultListModel[String]()
  private val list = JList[String](model)
  private val right = JPanel(BorderLayout())
  private var selected: Option[String] = None
  // declared before anything that builds the form (fields initialize in order)
  private val preview = EnemyPreview(() => current)

  list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  list.setCellRenderer(new DefaultListCellRenderer:
    override def getListCellRendererComponent(l: JList[?], v: Any, i: Int, sel: Boolean, focus: Boolean): java.awt.Component =
      val c = super.getListCellRendererComponent(l, v, i, sel, focus).asInstanceOf[JLabel]
      state.cfg.enemies.find(_.name == v).foreach { e =>
        c.setIcon(EnemyArt.icon(e, 22))
        c.setText(if e.boss then s"$v  (boss)" else v.toString)
      }
      c)
  list.addListSelectionListener(e => if !e.getValueIsAdjusting then
    val v = Option(list.getSelectedValue)
    if v != selected then
      selected = v
      showForm())

  private val buttons = Controls.row(
    Controls.button("New", "Add a new enemy type")(add(None)),
    Controls.button("Duplicate", "Copy the selected type")(selected.foreach(n => add(Some(n)))),
    Controls.button("Delete", "Remove the selected type (only when nothing uses it)")(delete()))
  private val left = JPanel(BorderLayout())
  left.add(JScrollPane(list), BorderLayout.CENTER)
  left.add(buttons, BorderLayout.SOUTH)
  left.setPreferredSize(Dimension(260, 600))
  add(left, BorderLayout.WEST)
  add(right, BorderLayout.CENTER)
  rebuild()

  def rebuild(): Unit =
    val keep = selected
    model.clear()
    state.cfg.enemies.foreach(e => model.addElement(e.name))
    selected = None
    val pick = keep.filter(n => state.cfg.enemies.exists(_.name == n)).orElse(state.cfg.enemies.headOption.map(_.name))
    pick.foreach(n => list.setSelectedValue(n, true))
    if pick.isEmpty then showForm()

  private def add(copyOf: Option[String]): Unit =
    val base = copyOf.flatMap(n => state.cfg.enemies.find(_.name == n)).getOrElse(
      EnemySpec("New enemy", 30f, 1.2f, com.badlogic.gdx.graphics.Color.valueOf("C8B48CFF"), 16f, Resistances(), None))
    val name = ConfigEdits.freshName(state.cfg, copyOf.fold("New enemy")(n => s"$n copy"))
    state.update(c => c.copy(enemies = c.enemies :+ base.copy(name = name)))
    selected = Some(name)
    rebuild()

  private def delete(): Unit = selected.foreach { n =>
    ConfigEdits.deleteEnemy(state.cfg, n) match
      case Left(uses) =>
        JOptionPane.showMessageDialog(this, s"$n is still used:\n" + uses.take(15).mkString("\n") +
          (if uses.size > 15 then s"\n… and ${uses.size - 15} more" else ""), "Cannot delete", JOptionPane.WARNING_MESSAGE)
      case Right(next) =>
        state.update(_ => next)
        selected = None
        rebuild()
  }

  private def current: Option[EnemySpec] = selected.flatMap(n => state.cfg.enemies.find(_.name == n))

  private def edit(f: EnemySpec => EnemySpec): Unit =
    selected.foreach(n => state.update(c => ConfigEdits.updateEnemy(c, n)(f)))
    preview.repaint()
    list.repaint()

  private def showForm(): Unit =
    right.removeAll()
    current.foreach { e =>
      val f = Form()
      val others = state.cfg.enemies.map(_.name).filterNot(_ == e.name)
      f.section("Look")
      f.field("Name", Controls.text(e.name, rename), "Renaming updates every wave and reference that uses it")
      f.field("Shape", Controls.dropdown(EnemyShape.values.toVector, e.shape, s => edit(_.copy(shape = s))))
      f.field("Color", Controls.color(e.color, c => edit(_.copy(color = c))))
      f.field("Size", Controls.slider(4, 64, 1, e.size, v => edit(_.copy(size = v.toFloat)), "px"),
        "In game units: a tile is 64 wide on a 10x10 map and about 46 on a 14x14 map")
      f.full(preview)

      f.section("Stats")
      f.field("Health", Controls.slider(1, 3000, 1, e.baseHp, v => edit(_.copy(baseHp = v.toFloat)), log = true))
      f.field("Speed", Controls.slider(0.1, 4, 0.05, e.baseSpeed, v => edit(_.copy(baseSpeed = v.toFloat)), "tiles/s"))
      f.field("Speed variance", Controls.slider(0, 1, 0.05, e.speedVariance, v => edit(_.copy(speedVariance = v.toFloat))),
        "Each run rolls this type up to this much faster (0.2 = up to 20%)")
      f.field("Kill reward", Controls.slider(0, 5, 0.1, e.killReward, v => edit(_.copy(killReward = v.toFloat)), "x"),
        "Multiplies the economy's gold per kill")
      f.field("Lives on leak", Controls.stepper(1, 10, e.leakCost, v => edit(_.copy(leakCost = v))))
      f.field("Boss", Controls.checkbox(e.boss, v => { edit(_.copy(boss = v)); list.repaint() }))
      f.field("Heavy", Controls.checkbox(e.heavy, v => edit(_.copy(heavy = v))),
        "Heavy enemies trigger biome rules such as the shipping yard's crushed hazards")

      f.section("Resistances  (negative = weakness)")
      def res(label: String, get: Resistances => Float, set: (Resistances, Float) => Resistances): Unit =
        f.field(label, Controls.slider(-100, 100, 5, math.round(get(e.resist) * 100), v =>
          edit(x => x.copy(resist = set(x.resist, (v / 100).toFloat))), "%"))
      res("Kinetic", _.kinetic, (r, v) => r.copy(kinetic = v))
      res("Fire", _.fire, (r, v) => r.copy(fire = v))
      res("Frost (slow)", _.slow, (r, v) => r.copy(slow = v))
      res("Splash", _.splash, (r, v) => r.copy(splash = v))
      res("Explosion", _.explosion, (r, v) => r.copy(explosion = v))
      res("Shock", _.shock, (r, v) => r.copy(shock = v))

      f.section("Special abilities")
      val auraKinds: Vector[Option[AuraKind]] = None +: AuraKind.values.toVector.map(Some(_))
      f.field("Aura", Controls.dropdown(auraKinds, e.aura.map(_.kind), k => {
        edit(x => x.copy(aura = k.map(kind => x.aura.fold(Aura(kind, 1.5f, 0.25f))(_.copy(kind = kind)))))
        showForm()
      }, _.fold("none")(auraLabel)))
      e.aura.foreach { a =>
        f.field("Aura radius", Controls.slider(0.5, 5, 0.1, a.radius, v => edit(x => x.copy(aura = x.aura.map(_.copy(radius = v.toFloat)))), "tiles"))
        f.field("Aura strength", Controls.slider(0, 100, 5, math.round(a.magnitude * 100), v =>
          edit(x => x.copy(aura = x.aura.map(_.copy(magnitude = (v / 100).toFloat)))), "%"))
      }
      f.field("Promotes to", Controls.dropdown(None +: others.map(Some(_)), e.promotesTo,
        p => edit(_.copy(promotesTo = p)), _.getOrElse("—")),
        "The balancer hardens a level by turning some of this type into the next one")
      f.field("On death spawns", Controls.dropdown(None +: others.map(Some(_)), e.onDeath.map(_._1), s => {
        edit(x => x.copy(onDeath = s.map(n => (n, x.onDeath.fold(2)(_._2)))))
        showForm()
      }, _.getOrElse("nothing")))
      e.onDeath.foreach { (_, count) =>
        f.field("  how many", Controls.stepper(1, 12, count, v => edit(x => x.copy(onDeath = x.onDeath.map((n, _) => (n, v))))))
      }
      val sides: Vector[Option[RouteSide]] = None +: RouteSide.values.toVector.map(Some(_))
      f.field("Fork preference", Controls.dropdown(sides, e.sidePreference, s => edit(_.copy(sidePreference = s)),
        _.fold("random")(_.toString.toLowerCase)), "Which lane it takes where the road forks")

      val uses = ConfigEdits.uses(state.cfg, e.name)
      f.section(s"Used in ${uses.size} place${if uses.size == 1 then "" else "s"}")
      uses.take(12).foreach(u => f.full(JLabel(u)))
      if uses.size > 12 then f.full(JLabel(s"… and ${uses.size - 12} more"))
      right.add(JScrollPane(f.finish()), BorderLayout.CENTER)
    }
    right.revalidate()
    right.repaint()

  private def auraLabel(k: AuraKind): String = k match
    case AuraKind.SpeedBoost => "speed boost (nearby enemies walk faster)"
    case AuraKind.Armor      => "armor (nearby enemies take less damage)"
    case AuraKind.TowerChill => "tower chill (nearby towers fire slower)"

  private def rename(to0: String): Unit =
    val to = to0.trim
    selected.foreach { from =>
      if to.isEmpty || to == from then ()
      else if state.cfg.enemies.exists(_.name == to) then
        JOptionPane.showMessageDialog(this, s"There is already an enemy called $to.", "Rename", JOptionPane.WARNING_MESSAGE)
        showForm()
      else
        state.update(c => ConfigEdits.renameEnemy(c, from, to))
        selected = Some(to)
        rebuild()
    }

// -- levels and waves ------------------------------------------------------------------

final class LevelsTab(state: EditorState) extends JPanel(BorderLayout()):
  private var set: LevelSet = LevelSet.Campaign
  private var index = 0
  private val model = DefaultListModel[String]()
  private val list = JList[String](model)
  private val right = JPanel(BorderLayout())
  private var summary: JLabel = JLabel()
  private var waveSummaries = Vector.empty[JLabel]
  private var listing = false

  private val setChoice = JComboBox[String]()
  setChoice.addActionListener(_ => if !listing && setChoice.getSelectedIndex >= 0 then
    set = ConfigEdits.levelSets(state.cfg)(setChoice.getSelectedIndex)
    index = 0
    rebuild())
  list.addListSelectionListener(e => if !e.getValueIsAdjusting && !listing && list.getSelectedIndex >= 0 then
    index = list.getSelectedIndex
    showLevel())

  private val left = JPanel(BorderLayout())
  left.add(setChoice, BorderLayout.NORTH)
  left.add(JScrollPane(list), BorderLayout.CENTER)
  left.add(JPanel(GridLayout(0, 2)).tap { p =>
    p.add(Controls.button("Duplicate", "Copy this level (route included) to the end")(duplicate()))
    p.add(Controls.button("Delete", "Remove this level")(deleteLevel()))
    p.add(Controls.button("Move up")(move(-1)))
    p.add(Controls.button("Move down")(move(1)))
  }, BorderLayout.SOUTH)
  left.setPreferredSize(Dimension(260, 600))
  add(left, BorderLayout.WEST)
  add(right, BorderLayout.CENTER)
  rebuild()

  private def levels = ConfigEdits.levels(state.cfg, set)
  private def level: Option[LevelConfig] = levels.lift(index)

  def rebuild(): Unit =
    listing = true
    val sets = ConfigEdits.levelSets(state.cfg)
    if !sets.contains(set) then set = LevelSet.Campaign
    setChoice.removeAllItems()
    sets.foreach(s => setChoice.addItem(s match
      case LevelSet.Campaign    => "Campaign levels"
      case LevelSet.OfBiome(id) => s"$id levels"))
    setChoice.setSelectedIndex(sets.indexOf(set))
    model.clear()
    levels.zipWithIndex.foreach((l, i) => model.addElement(s"${i + 1}. ${l.name}"))
    index = math.max(0, math.min(index, levels.size - 1))
    if levels.nonEmpty then list.setSelectedIndex(index)
    listing = false
    showLevel()

  private def editLevel(f: LevelConfig => LevelConfig, structural: Boolean = false): Unit =
    state.update(c => ConfigEdits.updateLevel(c, set, index)(f))
    if structural then showLevel() else refreshSummaries()

  private def editWave(wi: Int, structural: Boolean = false)(f: WaveSpec => WaveSpec): Unit =
    editLevel(l => l.copy(waves = l.waves.updated(wi, f(l.waves(wi)))), structural)

  private def duplicate(): Unit = level.foreach { l =>
    state.update(c => ConfigEdits.withLevels(c, set, levels :+ l.copy(name = s"${l.name} copy")))
    index = levels.size - 1
    rebuild()
  }

  private def deleteLevel(): Unit = level.foreach { l =>
    if levels.size <= 1 then JOptionPane.showMessageDialog(this, "A level set needs at least one level.")
    else if JOptionPane.showConfirmDialog(this, s"Delete level ${index + 1} (${l.name})?", "Delete",
        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION then
      state.update(c => ConfigEdits.withLevels(c, set, levels.patch(index, Nil, 1)))
      rebuild()
  }

  private def move(dir: Int): Unit =
    val to = index + dir
    if level.isDefined && to >= 0 && to < levels.size then
      val ls = levels
      state.update(c => ConfigEdits.withLevels(c, set, ls.updated(index, ls(to)).updated(to, ls(index))))
      index = to
      rebuild()

  private def refreshSummaries(): Unit =
    level.foreach { l =>
      summary.setText(levelSummary(l))
      l.waves.zip(waveSummaries).foreach((w, label) => label.setText(waveSummary(w)))
      model.set(index, s"${index + 1}. ${l.name}")
    }

  private def levelSummary(l: LevelConfig): String =
    val enemies = l.waves.map(_.count).sum
    val hp      = l.waves.map(ConfigEdits.waveHp(state.cfg, _)).sum
    val leak    = l.waves.map(ConfigEdits.waveLeak(state.cfg, _)).sum
    f"<html><b>${l.waves.size} waves · $enemies enemies · ${hp}%.0f total health · up to $leak lives if all leak</b></html>"

  private def waveSummary(w: WaveSpec): String =
    f"${w.count} enemies · ${ConfigEdits.waveHp(state.cfg, w)}%.0f health · ${ConfigEdits.waveLeak(state.cfg, w)} lives if all leak"

  private def showLevel(): Unit =
    right.removeAll()
    level.foreach { l =>
      val f = Form()
      f.section(s"Level ${index + 1}")
      f.field("Name", Controls.text(l.name, n => if n.trim.nonEmpty then editLevel(_.copy(name = n.trim))))
      val routeMax = (l.route.waypoints ++ l.route.branches.flatMap(_.waypoints)).flatMap(p => Seq(p.x, p.y)).maxOption.getOrElse(0)
      f.field("Grid size", Controls.stepper(math.max(4, routeMax + 1), 30, l.gridSize.getOrElse(Config.game.board.gridSize),
        v => editLevel(_.copy(gridSize = Some(v)))), "Tiles per side; cannot be smaller than the route needs")
      f.field("Start gold", Controls.slider(0, 1000, 10, l.startGold.getOrElse(state.cfg.economy.startGold),
        v => editLevel(_.copy(startGold = Some(v.toInt)))), "Gold at the start of this level (it does not carry over)")
      summary = JLabel(levelSummary(l))
      f.full(summary)

      waveSummaries = Vector.empty
      l.waves.zipWithIndex.foreach((w, wi) => f.full(waveCard(l, w, wi)))
      f.full(Controls.row(Controls.button("+ Add wave", "Adds a copy of the last wave")(
        editLevel(x => x.copy(waves = x.waves :+ x.waves.lastOption.getOrElse(ConfigEdits.wave(1f, 1f,
          Vector(state.cfg.enemies.head.name -> 5)))), structural = true))))
      val scroll = JScrollPane(f.finish())
      scroll.getVerticalScrollBar.setUnitIncrement(24)
      right.add(scroll, BorderLayout.CENTER)
    }
    right.revalidate()
    right.repaint()

  private def waveCard(l: LevelConfig, w: WaveSpec, wi: Int): JComponent =
    val f = Form()
    val sum = JLabel(waveSummary(w))
    waveSummaries :+= sum
    f.full(Controls.row(sum,
      Controls.button("▲", "Move this wave earlier")(swap(wi, wi - 1)),
      Controls.button("▼", "Move this wave later")(swap(wi, wi + 1)),
      Controls.button("Duplicate")(editLevel(x => x.copy(waves = x.waves.patch(wi + 1, Seq(x.waves(wi)), 0)), structural = true)),
      Controls.button("Delete")(if l.waves.size > 1 then
        editLevel(x => x.copy(waves = x.waves.patch(wi, Nil, 1)), structural = true))))
    f.field("Spawn interval", Controls.slider(0.1, 3, 0.05, w.spawnInterval,
      v => editWave(wi)(x => x.copy(spawnInterval = v.toFloat)), "s"), "Seconds between two enemies of this wave")
    f.field("Health scale", Controls.slider(0.25, 4, 0.05, w.hpScale, v => editWave(wi)(x => x.copy(hpScale = v.toFloat)), "x"),
      "Multiplies the health of every enemy in this wave")
    val names = state.cfg.enemies.map(_.name)
    w.composition.zipWithIndex.foreach { case ((name, count), ci) =>
      def setComp(f: Vector[(String, Int)] => Vector[(String, Int)], structural: Boolean = false) =
        editWave(wi, structural)(x => ConfigEdits.wave(x.spawnInterval, x.hpScale, f(x.composition)))
      val icon = state.cfg.enemies.find(_.name == name).map(e => JLabel(EnemyArt.icon(e, 20))).getOrElse(JLabel())
      f.field(s"Enemy ${ci + 1}", Controls.row(icon,
        Controls.dropdown(names, name, n => setComp(_.updated(ci, (n, count)), structural = true)),
        Controls.stepper(1, 300, count, v => setComp(c => c.updated(ci, (c(ci)._1, v)))),
        Controls.button("✕", "Remove from this wave")(if w.composition.size > 1 then setComp(_.patch(ci, Nil, 1), structural = true))))
    }
    f.full(Controls.row(Controls.button("+ Add enemy to this wave") {
      val next = names.find(n => !w.composition.exists(_._1 == n)).getOrElse(names.head)
      editWave(wi, structural = true)(x => ConfigEdits.wave(x.spawnInterval, x.hpScale, x.composition :+ (next -> 1)))
    }))
    titled(s"Wave ${wi + 1}", f.finish())

  private def swap(a: Int, b: Int): Unit = level.foreach { l =>
    if a >= 0 && b >= 0 && a < l.waves.size && b < l.waves.size then
      editLevel(x => x.copy(waves = x.waves.updated(a, x.waves(b)).updated(b, x.waves(a))), structural = true)
  }

extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }
