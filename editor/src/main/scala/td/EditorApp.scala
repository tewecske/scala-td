package td

import java.awt.{BasicStroke, BorderLayout, Color as AwtColor, Dimension, Font, Graphics, Graphics2D, GridLayout, Polygon,
  RenderingHints}
import java.awt.event.{WindowAdapter, WindowEvent}
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import td.ConfigEdits.LevelSet

/** The config editor (levels, waves, enemies, towers, biomes, talents, economy): a separate window, not part of the game.
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
  private val biomes  = BiomesTab(state)
  private val towers  = TowersTab(state)
  private val talents = TalentsTab(state)
  private val economy = EconomyTab(state)
  private val tabs = JTabbedPane()

  setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
  setSize(1280, 860)
  setLocationRelativeTo(null)
  tabs.addTab("Enemies", enemies)
  tabs.addTab("Levels & waves", levels)
  tabs.addTab("Towers", towers)
  tabs.addTab("Biomes", biomes)
  tabs.addTab("Talents", talents)
  tabs.addTab("Economy & combat", economy)

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
    biomes.rebuild()
    towers.rebuild()
    talents.rebuild()
    economy.rebuild()

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
        case 4 => tabs.setSelectedIndex(2)
        case 5 => shot(File(dir, "editor-towers.png"))
        case 6 => tabs.setSelectedIndex(3)
        case 7 => shot(File(dir, "editor-biomes.png"))
        case 8 => tabs.setSelectedIndex(4)
        case 9 => shot(File(dir, "editor-talents.png"))
        case 10 => tabs.setSelectedIndex(5)
        case 11 => shot(File(dir, "editor-economy.png"))
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

// -- biomes ------------------------------------------------------------------------------

/** A biome's resistances (added to every enemy played there) and its environment rule. */
final class BiomesTab(state: EditorState) extends JPanel(BorderLayout()):
  import BiomesTab.*
  private val model = DefaultListModel[String]()
  private val list = JList[String](model)
  private val right = JPanel(BorderLayout())
  private var selected: Option[String] = None
  private var listing = false

  list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  list.addListSelectionListener(e => if !e.getValueIsAdjusting && !listing && list.getSelectedIndex >= 0 then
    selected = state.cfg.biomes.lift(list.getSelectedIndex).map(_.id)
    showForm())
  private val left = JPanel(BorderLayout())
  left.add(JScrollPane(list), BorderLayout.CENTER)
  left.setPreferredSize(Dimension(260, 600))
  add(left, BorderLayout.WEST)
  add(right, BorderLayout.CENTER)
  rebuild()

  def rebuild(): Unit =
    listing = true
    model.clear()
    state.cfg.biomes.foreach(b => model.addElement(label(b)))
    if !selected.exists(id => state.cfg.biomes.exists(_.id == id)) then
      selected = state.cfg.biomes.find(b => !b.starter && b.id != "tutorial").orElse(state.cfg.biomes.headOption).map(_.id)
    val i = state.cfg.biomes.indexWhere(b => selected.contains(b.id))
    if i >= 0 then list.setSelectedIndex(i)
    listing = false
    showForm()

  private def label(b: Biome): String =
    if b.starter then s"${b.name}  (level 1)" else if b.id == "tutorial" then s"${b.name}  (tutorial)" else b.name

  private def current: Option[Biome] = selected.flatMap(id => state.cfg.biomes.find(_.id == id))

  private def edit(f: Biome => Biome, structural: Boolean = false): Unit =
    selected.foreach(id => state.update(c => ConfigEdits.updateBiome(c, id)(f)))
    current.foreach(b => model.set(list.getSelectedIndex, label(b)))
    if structural then showForm()

  private def ruleLabel(kind: String): String = kind match
    case "openFloorPlan" => "open floor plan (crowded enemies are armored)"
    case "crushed"       => "crushed (dying heavy enemies leave a slowing patch)"
    case "recycling"     => "recycling (bigger waves, cheaper leaks, more gold)"
    case other           => other

  private def showForm(): Unit =
    right.removeAll()
    current.foreach { b =>
      val f = Form()
      f.section(b.name)
      f.field("Name", Controls.text(b.name, n => if n.trim.nonEmpty then edit(_.copy(name = n.trim))))
      f.full(JLabel(
        if b.starter then "Every run's first level is played here. It must stay plain: no resistances, no rule."
        else if b.id == "tutorial" then "The tutorial plays its own levels here."
        else "A run rolls one of these biomes for levels 2 and on."))

      if !b.starter then
        val t = b.resistTheme
        val kinds = DamageKind.values.toVector
        def kindName(k: DamageKind) = if k == DamageKind.Slow then "frost (slow)" else k.toString.toLowerCase
        f.section("Resistances  (added to every enemy here; negative = weakness)")
        f.field("Resistance", Controls.dropdown(kinds, t.primary, k => edit(x => x.copy(resistTheme = x.resistTheme.copy(primary = k))), kindName))
        f.field("  amount", Controls.slider(-100, 100, 5, math.round(t.primaryAmount * 100),
          v => edit(x => x.copy(resistTheme = x.resistTheme.copy(primaryAmount = (v / 100).toFloat))), "%"))
        f.field("Second resistance", Controls.dropdown(kinds, t.secondary, k => edit(x => x.copy(resistTheme = x.resistTheme.copy(secondary = k))), kindName))
        f.field("  amount", Controls.slider(-100, 100, 5, math.round(t.secondaryAmount * 100),
          v => edit(x => x.copy(resistTheme = x.resistTheme.copy(secondaryAmount = (v / 100).toFloat))), "%"),
          "Leave at 0% for a biome with one resistance")

        f.section("Environment rule")
        val rules: Vector[Option[String]] = None +: ConfigEdits.EnvironmentRules.map(r => Some(r.kind))
        f.field("Rule", Controls.dropdown(rules, b.environment.map(_.kind), k =>
          edit(_.copy(environment = k.flatMap(kind => ConfigEdits.EnvironmentRules.find(_.kind == kind))), structural = true),
          _.fold("none")(ruleLabel)))
        b.environment.foreach { env =>
          env.params.toVector.sortBy(_._1).foreach { (key, value) =>
            val (name, lo, hi, step, unit, tip) = ParamUi.getOrElse(key, (key, 0d, 10d, 0.1, "", ""))
            val scale = if Percent(key) then 100 else 1
            f.field(name, Controls.slider(lo, hi, step, value * scale, v =>
              edit(x => x.copy(environment = x.environment.map(e => e.copy(params = e.params.updated(key, (v / scale).toFloat))))),
              unit), tip)
          }
        }
      right.add(JScrollPane(f.finish()), BorderLayout.CENTER)
    }
    right.revalidate()
    right.repaint()

object BiomesTab:
  /** Slider range, unit and help for each environment parameter. */
  val ParamUi: Map[String, (String, Double, Double, Double, String, String)] = Map(
    "armorPerNeighbor" -> ("Armor per neighbor", 0, 100, 5, "%", "Damage reduction an enemy gets for each other enemy near it"),
    "radius"           -> ("Radius", 0.5, 4, 0.1, "tiles", "How far the rule reaches"),
    "hazardDuration"   -> ("Hazard lasts", 0.5, 10, 0.5, "s", "How long the patch a heavy enemy leaves behind stays"),
    "slowFactor"       -> ("Hazard slow", 0, 100, 5, "%", "How much the patch slows enemies walking through it"),
    "livesLossMult"    -> ("Lives per leak", 0, 200, 5, "%", "Share of the usual lives a leaking enemy costs"),
    "killGoldBonus"    -> ("Extra gold per kill", 0, 10, 0.5, "gold", "Added to the gold every kill pays"),
    "swarmSizeMult"    -> ("Wave size", 50, 300, 5, "%", "Every group in a wave is this big compared with the level's wave"))
  val Percent = Set("armorPerNeighbor", "slowFactor", "livesLossMult", "swarmSizeMult")

// -- towers ------------------------------------------------------------------------------

/** A tower's range drawn over map tiles, with the tower in the middle. */
final class TowerPreview(tower: () => Option[TowerSpec]) extends JComponent:
  setPreferredSize(Dimension(300, 230))
  override def paintComponent(g0: Graphics): Unit =
    val g = g0.asInstanceOf[Graphics2D]
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setColor(AwtColor(2, 26, 1))
    g.fillRect(0, 0, getWidth, getHeight)
    tower().foreach { t =>
      val tile  = 20
      val tiles = 11
      val ox    = (getWidth - tiles * tile) / 2
      val oy    = 8
      for x <- 0 until tiles; y <- 0 until tiles do
        g.setColor(if (x + y) % 2 == 0 then AwtColor(50, 138, 46) else AwtColor(44, 124, 40))
        g.fillRect(ox + x * tile, oy + y * tile, tile, tile)
      val cx = ox + tiles * tile / 2.0
      val cy = oy + tiles * tile / 2.0
      val r  = t.range * tile
      g.setColor(AwtColor(255, 230, 165, 60))
      g.fill(java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
      g.setColor(AwtColor(255, 230, 165))
      g.draw(java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
      if t.splashRadius > 0f then
        val s = t.splashRadius * tile
        g.setColor(AwtColor(255, 140, 90, 140))
        g.draw(java.awt.geom.Ellipse2D.Double(cx + r * 0.6 - s, cy - s, 2 * s, 2 * s))
      g.setColor(Controls.toAwt(t.color))
      g.fillRect((cx - tile / 2.0).toInt, (cy - tile / 2.0).toInt, tile, tile)
      g.setColor(AwtColor(255, 230, 165))
      g.drawString(f"range ${t.range}%.1f tiles" + (if t.splashRadius > 0f then f", blast ${t.splashRadius}%.1f" else ""),
        ox, oy + tiles * tile + 16)
    }

final class TowersTab(state: EditorState) extends JPanel(BorderLayout()):
  import ConfigEdits.TowerAccess
  private val model = DefaultListModel[String]()
  private val list = JList[String](model)
  private val right = JPanel(BorderLayout())
  private var selected: Option[String] = None
  private var summary = JLabel()
  // declared before anything that builds the form (fields initialize in order)
  private val preview = TowerPreview(() => current)

  list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  list.setCellRenderer(new DefaultListCellRenderer:
    override def getListCellRendererComponent(l: JList[?], v: Any, i: Int, sel: Boolean, focus: Boolean): java.awt.Component =
      val c = super.getListCellRendererComponent(l, v, i, sel, focus).asInstanceOf[JLabel]
      state.cfg.towers.find(_.name == v).foreach { t =>
        c.setIcon(swatch(t))
        c.setText(s"$v  (${accessLabel(ConfigEdits.towerAccess(state.cfg, t.name)).takeWhile(_ != ':')})")
      }
      c)
  list.addListSelectionListener(e => if !e.getValueIsAdjusting then
    val v = Option(list.getSelectedValue)
    if v != selected then
      selected = v
      showForm())

  private val left = JPanel(BorderLayout())
  private val perRun = JPanel(BorderLayout())
  left.add(perRun, BorderLayout.NORTH)
  left.add(JScrollPane(list), BorderLayout.CENTER)
  left.add(Controls.row(
    Controls.button("New", "Add a new tower type")(add(None)),
    Controls.button("Duplicate", "Copy the selected tower")(selected.foreach(n => add(Some(n)))),
    Controls.button("Delete", "Remove the selected tower")(delete())), BorderLayout.SOUTH)
  left.setPreferredSize(Dimension(260, 600))
  add(left, BorderLayout.WEST)
  add(right, BorderLayout.CENTER)
  rebuild()

  def rebuild(): Unit =
    perRun.removeAll()
    perRun.add(Controls.row(JLabel("Towers per run"), Controls.stepper(1, 6, state.cfg.loadout.maxSelected,
      v => state.update(c => c.copy(loadout = c.loadout.copy(maxSelected = v))))), BorderLayout.CENTER)
    perRun.revalidate()
    val keep = selected
    model.clear()
    state.cfg.towers.foreach(t => model.addElement(t.name))
    selected = None
    val pick = keep.filter(n => state.cfg.towers.exists(_.name == n)).orElse(state.cfg.towers.headOption.map(_.name))
    pick.foreach(n => list.setSelectedValue(n, true))
    if pick.isEmpty then showForm()

  private def swatch(t: TowerSpec): Icon = new Icon:
    def getIconWidth: Int = 18
    def getIconHeight: Int = 18
    def paintIcon(c: java.awt.Component, g: Graphics, x: Int, y: Int): Unit =
      g.setColor(Controls.toAwt(t.color))
      g.fillRect(x + 2, y + 2, 14, 14)

  private def current: Option[TowerSpec] = selected.flatMap(n => state.cfg.towers.find(_.name == n))

  private def edit(f: TowerSpec => TowerSpec): Unit =
    selected.foreach(n => state.update(c => ConfigEdits.updateTower(c, n)(f)))
    current.foreach(t => summary.setText(describe(t)))
    preview.repaint()
    list.repaint()

  private def add(copyOf: Option[String]): Unit =
    val base = copyOf.flatMap(n => state.cfg.towers.find(_.name == n)).getOrElse(
      TowerSpec("New tower", 60, com.badlogic.gdx.graphics.Color.valueOf("C8B48CFF"), 3f, 0.6f, 10f, 13f, DamageKind.Kinetic, 0f))
    val taken = state.cfg.towers.map(_.name).toSet
    val stem  = copyOf.fold("New tower")(n => s"$n copy")
    val name  = if !taken(stem) then stem else Iterator.from(2).map(i => s"$stem $i").find(n => !taken(n)).get
    state.update(c => ConfigEdits.setTowerAccess(c.copy(towers = c.towers :+ base.copy(name = name)), name, TowerAccess.Unlocked))
    selected = Some(name)
    rebuild()

  private def delete(): Unit = selected.foreach { n =>
    val twists = state.cfg.twists.filter(_.targetTower == n).map(_.name)
    if twists.nonEmpty then
      JOptionPane.showMessageDialog(this, s"$n is the target of the twist(s): ${twists.mkString(", ")}", "Cannot delete",
        JOptionPane.WARNING_MESSAGE)
    else if state.cfg.loadout.defaultSelected == Vector(n) then
      JOptionPane.showMessageDialog(this, s"$n is the only tower a new player starts with.", "Cannot delete",
        JOptionPane.WARNING_MESSAGE)
    else
      state.update { c =>
        val l = ConfigEdits.setTowerAccess(c, n, TowerAccess.Unlocked).loadout
        c.copy(towers = c.towers.filterNot(_.name == n),
          loadout = l.copy(unlocked = l.unlocked.filterNot(_ == n)))
      }
      selected = None
      rebuild()
  }

  private def accessLabel(a: TowerAccess): String = a match
    case TowerAccess.Starting => "starting: in a new player's loadout"
    case TowerAccess.Unlocked => "free: can be picked for any run"
    case TowerAccess.Locked   => "locked: bought with meta currency, maybe after a level"

  private def describe(t: TowerSpec): String =
    val dps = t.damage / math.max(0.01f, t.fireInterval)
    f"<html><b>${dps}%.1f damage per second</b> (${1f / math.max(0.01f, t.fireInterval)}%.2f shots/s) · " +
      f"${dps / math.max(1, t.cost) * 100}%.1f per 100 gold" +
      (if t.splashRadius > 0f then " · hits everything in the blast" else "") + "</html>"

  private def rename(to0: String): Unit =
    val to = to0.trim
    selected.foreach { from =>
      if to.isEmpty || to == from then ()
      else if state.cfg.towers.exists(_.name == to) then
        JOptionPane.showMessageDialog(this, s"There is already a tower called $to.", "Rename", JOptionPane.WARNING_MESSAGE)
        showForm()
      else
        state.update(c => ConfigEdits.renameTower(c, from, to))
        selected = Some(to)
        rebuild()
    }

  private def showForm(): Unit =
    right.removeAll()
    current.foreach { t =>
      val f = Form()
      f.section("Look")
      f.field("Name", Controls.text(t.name, rename),
        "Renaming updates the loadout and twists. Saved progress that bought this tower keeps the old name.")
      f.field("Color", Controls.color(t.color, c => edit(_.copy(color = c))))
      f.full(preview)

      f.section("Stats")
      summary = JLabel(describe(t))
      f.full(summary)
      f.field("Cost", Controls.slider(5, 500, 5, t.cost, v => edit(_.copy(cost = v.toInt)), "gold"), "Gold to build one in a run")
      f.field("Damage", Controls.slider(1, 300, 0.5, t.damage, v => edit(_.copy(damage = v.toFloat)), "per hit", log = true))
      f.field("Reload", Controls.slider(0.05, 4, 0.05, t.fireInterval, v => edit(_.copy(fireInterval = v.toFloat)), "s"),
        "Seconds between two shots (lower fires faster)")
      f.field("Range", Controls.slider(0.5, 10, 0.1, t.range, v => edit(_.copy(range = v.toFloat)), "tiles"))
      f.field("Damage type", Controls.dropdown(DamageKind.values.toVector, t.kind, k => edit(_.copy(kind = k)),
        k => if k == DamageKind.Slow then "frost" else k.toString.toLowerCase),
        "Which resistance it meets and which damage talents boost it. Burn and slow are set below")
      f.field("Blast radius", Controls.slider(0, 5, 0.1, t.splashRadius, v => edit(_.copy(splashRadius = v.toFloat)), "tiles"),
        "0 hits one enemy; above 0 hits every enemy within this radius of the impact")
      f.field("Shot speed", Controls.slider(1, 40, 0.25, t.projectileSpeed, v => edit(_.copy(projectileSpeed = v.toFloat)), "tiles/s"))
      f.field("Targets", Controls.dropdown(Targeting.values.toVector, t.targeting, m => edit(_.copy(targeting = m)), {
        case Targeting.First   => "first: furthest along the road"
        case Targeting.Weakest => "weakest: least health left"
      }))

      f.section("What a hit does besides damage")
      f.field("Burn", Controls.slider(0, 50, 0.5, t.burnDps, v => edit(_.copy(burnDps = v.toFloat)), "damage/s"),
        "0 = no burn. Burning enemies take this much fire damage per second")
      f.field("  burn lasts", Controls.slider(0, 10, 0.25, t.burnDuration, v => edit(_.copy(burnDuration = v.toFloat)), "s"))
      f.field("Slow", Controls.slider(0, 95, 5, math.round((1f - t.slowFactor) * 100), v =>
        edit(_.copy(slowFactor = 1f - (v / 100).toFloat)), "% slower"), "0 = no slow. How much slower a hit enemy walks")
      f.field("  slow lasts", Controls.slider(0, 10, 0.25, t.slowDuration, v => edit(_.copy(slowDuration = v.toFloat)), "s"))
      f.field("Puddle bonus", Controls.slider(100, 300, 5, math.round(t.puddleMult * 100), v =>
        edit(_.copy(puddleMult = (v / 100).toFloat)), "% damage"), "Damage against enemies standing in a puddle (100% = no bonus)")
      f.field("Lands where aimed", Controls.checkbox(t.groundTarget, v => edit(_.copy(groundTarget = v))),
        "On: the shot flies to where the target was and hits there (it can miss a moving enemy). Off: it follows the target")

      f.section("Availability")
      val access = ConfigEdits.towerAccess(state.cfg, t.name)
      f.field("Available", Controls.dropdown(TowerAccess.values.toVector, access, a => {
        state.update(c => ConfigEdits.setTowerAccess(c, t.name, a))
        list.repaint()
        showForm()
      }, accessLabel))
      if access == TowerAccess.Locked then
        f.field("Unlock cost", Controls.stepper(0, 500, state.cfg.loadout.unlockCosts.getOrElse(t.name, 30), v =>
          state.update(c => c.copy(loadout = c.loadout.copy(unlockCosts = c.loadout.unlockCosts.updated(t.name, v))))),
          "Meta currency to unlock it for good")
        f.field("Unlocks after clearing", Controls.stepper(0, 30, state.cfg.loadout.levelsToUnlock(t.name), v =>
          state.update(c => c.copy(loadout = c.loadout.copy(unlockLevels =
            if v == 0 then c.loadout.unlockLevels - t.name else c.loadout.unlockLevels.updated(t.name, v))))),
          "Levels the player must have cleared in one run before it can be bought (0 = any time)")
      val twists = state.cfg.twists.filter(_.targetTower == t.name)
      if twists.nonEmpty then f.full(JLabel(s"Run twists that change it: ${twists.map(_.name).mkString(", ")}"))
      right.add(JScrollPane(f.finish()), BorderLayout.CENTER)
    }
    right.revalidate()
    right.repaint()

// -- talents -----------------------------------------------------------------------------

/** The meta talent tree: what each talent costs, needs and does. */
final class TalentsTab(state: EditorState) extends JPanel(BorderLayout()):
  import TalentsTab.*
  private val model = DefaultListModel[String]()
  private val list = JList[String](model)
  private val right = JPanel(BorderLayout())
  private var selected: Option[String] = None // talent id
  private var listing = false

  list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
  list.addListSelectionListener(e => if !e.getValueIsAdjusting && !listing && list.getSelectedIndex >= 0 then
    selected = ordered.lift(list.getSelectedIndex).map(_.id)
    showForm())
  private val left = JPanel(BorderLayout())
  left.add(JScrollPane(list), BorderLayout.CENTER)
  left.add(Controls.row(
    Controls.button("New", "Add a new talent")(add(None)),
    Controls.button("Duplicate", "Copy the selected talent")(selected.foreach(id => add(Some(id)))),
    Controls.button("Delete", "Remove the selected talent (only when nothing needs it)")(delete())), BorderLayout.SOUTH)
  left.setPreferredSize(Dimension(260, 600))
  add(left, BorderLayout.WEST)
  add(right, BorderLayout.CENTER)
  rebuild()

  /** In the order the game shows them: by column, then config order. */
  private def ordered: Vector[Talent] =
    TalentLayer.values.toVector.flatMap(l => state.cfg.talents.filter(_.layer == l))

  private def label(t: Talent): String = s"${t.layer.toString.toLowerCase} · ${t.name}  (${t.cost})"

  def rebuild(): Unit =
    listing = true
    model.clear()
    ordered.foreach(t => model.addElement(label(t)))
    if !selected.exists(id => state.cfg.talents.exists(_.id == id)) then selected = ordered.headOption.map(_.id)
    val i = ordered.indexWhere(t => selected.contains(t.id))
    if i >= 0 then list.setSelectedIndex(i)
    listing = false
    showForm()

  private def current: Option[Talent] = selected.flatMap(id => state.cfg.talents.find(_.id == id))

  private def edit(f: Talent => Talent, structural: Boolean = false): Unit =
    selected.foreach(id => state.update(c => ConfigEdits.updateTalent(c, id)(f)))
    if structural then rebuild()
    else
      val i = ordered.indexWhere(t => selected.contains(t.id))
      current.foreach(t => if i >= 0 then model.set(i, label(t)))

  private def add(copyOf: Option[String]): Unit =
    val base = copyOf.flatMap(id => state.cfg.talents.find(_.id == id)).getOrElse(
      Talent("", "New talent", TalentLayer.Filler, 1, Vector.empty, ConfigEdits.TalentEffectKinds.head))
    val name = copyOf.fold("New talent")(_ => s"${base.name} copy")
    val id   = ConfigEdits.freshTalentId(state.cfg, name)
    state.update(c => c.copy(talents = c.talents :+ base.copy(id = id, name = name)))
    selected = Some(id)
    rebuild()

  private def delete(): Unit = selected.foreach { id =>
    ConfigEdits.talentUses(state.cfg, id) match
      case uses if uses.nonEmpty =>
        JOptionPane.showMessageDialog(this, uses.mkString("\n"), "Cannot delete", JOptionPane.WARNING_MESSAGE)
      case _ =>
        state.update(c => c.copy(talents = c.talents.filterNot(_.id == id)))
        selected = None
        rebuild()
  }

  private def showForm(): Unit =
    right.removeAll()
    current.foreach { t =>
      val f = Form()
      f.section(t.name)
      f.field("Name", Controls.text(t.name, n => if n.trim.nonEmpty then edit(_.copy(name = n.trim))))
      f.full(JLabel(s"id: ${t.id}  (saved progress refers to talents by id, so it does not change)"))
      f.field("Column", Controls.dropdown(TalentLayer.values.toVector, t.layer, l => edit(_.copy(layer = l), structural = true),
        _.toString.toLowerCase))
      val inColumn = state.cfg.talents.count(_.layer == t.layer)
      if inColumn > MaxPerColumn then
        f.full(JLabel(s"<html><font color='#B00020'>This column has $inColumn talents; the game screen fits $MaxPerColumn.</font></html>"))
      f.field("Cost", Controls.stepper(0, 99, t.cost, v => edit(_.copy(cost = v))), "Meta currency")

      f.section("Needs")
      val others = state.cfg.talents.filterNot(_.id == t.id)
      if others.isEmpty then f.full(JLabel("no other talents"))
      others.foreach { o =>
        f.full(Controls.checkbox(t.prereqs.contains(o.id), on =>
          edit(x => x.copy(prereqs = if on then (x.prereqs :+ o.id).distinct else x.prereqs.filterNot(_ == o.id))),
          s"${o.name}  (${o.layer.toString.toLowerCase})"))
      }

      f.section("Effect")
      val kinds = (ConfigEdits.TalentEffectKinds.map(_.kind) ++ Option.when(!known(t.effect.kind))(t.effect.kind)).distinct
      f.field("Does", Controls.dropdown(kinds, t.effect.kind, k => edit(x => x.copy(effect =
        ConfigEdits.TalentEffectKinds.find(_.kind == k).getOrElse(Effect(k, Map.empty))), structural = true), effectLabel))
      if !known(t.effect.kind) then f.full(JLabel("The game does not apply this effect yet."))
      t.effect.params.toVector.sortBy(_._1).foreach { (key, value) =>
        def set(v: Double) = edit(x => x.copy(effect = x.effect.copy(params = x.effect.params.updated(key, v.toFloat))))
        key match
          case "lives" | "slots" => f.field(paramLabel(key), Controls.stepper(0, 20, value.toInt, v => set(v)))
          case "gold"            => f.field(paramLabel(key), Controls.slider(0, 300, 5, value, set, "gold"))
          case _ => f.field(paramLabel(key), Controls.slider(0, 200, 1, math.round(value * 100), v => set(v / 100), "%"))
      }
      val uses = ConfigEdits.talentUses(state.cfg, t.id)
      if uses.nonEmpty then
        f.section("Needed by")
        uses.foreach(u => f.full(JLabel(u)))
      right.add(JScrollPane(f.finish()), BorderLayout.CENTER)
    }
    right.revalidate()
    right.repaint()

object TalentsTab:
  /** How many talents one column of the game's talent screen fits. */
  val MaxPerColumn = 9

  def known(kind: String): Boolean = ConfigEdits.TalentEffectKinds.exists(_.kind == kind)

  def effectLabel(kind: String): String = kind match
    case "damageBonus"     => "more damage (all towers)"
    case "rangeBonus"      => "more range (all towers)"
    case "fireRateBonus"   => "faster fire rate (all towers)"
    case "critChance"      => "critical hit chance"
    case "critDamage"      => "critical hit damage"
    case "kindDamageBonus" => "more damage for some damage types"
    case "shred"           => "hits shred resistance"
    case "goldBonus"       => "more gold per kill"
    case "extraLives"      => "extra lives"
    case "startGold"       => "more start gold every level"
    case "loadoutSlots"    => "more towers per run"
    case "reroll"          => "can reroll the draft"
    case other             => s"$other (not in effect)"

  /** What a talent does with its numbers, e.g. "+25% fire damage" or "+1 lives". */
  def describe(t: Talent): String =
    val p = t.effect.params
    def pct(v: Float) = s"${math.round(v * 100)}%"
    t.effect.kind match
      case "kindDamageBonus" =>
        p.toVector.filter(_._2 != 0f).sortBy(_._1)
          .map((k, v) => s"+${pct(v)} ${if k == "slow" then "frost" else k} damage").mkString(", ")
      case "shred"      => s"hits shred ${pct(p.getOrElse("perHit", 0f))} resistance, up to ${pct(p.getOrElse("max", 0f))}"
      case "critChance" => s"+${pct(p.getOrElse("chance", 0f))} critical hit chance"
      case "critDamage" => s"+${pct(p.getOrElse("mult", 0f))} critical hit damage"
      case "extraLives" => s"+${p.getOrElse("lives", 0f).toInt} lives"
      case "startGold"  => s"+${p.getOrElse("gold", 0f).toInt} start gold every level"
      case "loadoutSlots" => s"+${p.getOrElse("slots", 0f).toInt} towers per run"
      case k if p.contains("mult") => s"+${pct(p("mult"))} ${effectLabel(k).replaceFirst("^(more|faster) ", "")}"
      case k => effectLabel(k)

  def paramLabel(key: String): String = key match
    case "mult"   => "Bonus"
    case "chance" => "Chance"
    case "perHit" => "Per hit"
    case "max"    => "Up to"
    case "lives"  => "Lives"
    case "slots"  => "Slots"
    case "gold"   => "Gold"
    case "slow"   => "Frost"
    case k        => k.capitalize

// -- economy and combat ------------------------------------------------------------------

/** Run-wide numbers: gold, lives, meta currency, the draft and combat rules. */
final class EconomyTab(state: EditorState) extends JPanel(BorderLayout()):
  rebuild()

  def rebuild(): Unit =
    removeAll()
    val c = state.cfg
    val f = Form()
    def eco(g: EconomyConfig => EconomyConfig)  = state.update(x => x.copy(economy = g(x.economy)))
    def meta(g: MetaConfig => MetaConfig)       = state.update(x => x.copy(meta = g(x.meta)))
    def draft(g: DraftConfig => DraftConfig)    = state.update(x => x.copy(draft = g(x.draft)))
    def combat(g: CombatConfig => CombatConfig) = state.update(x => x.copy(combat = g(x.combat)))

    f.section("Economy")
    f.field("Lives", Controls.stepper(1, 50, c.economy.startLives, v => eco(_.copy(startLives = v))),
      "Lives a run starts with (talents add more); they carry over between levels")
    f.field("Gold per kill", Controls.slider(0, 30, 1, c.economy.killReward, v => eco(_.copy(killReward = v.toInt)), "gold"),
      "Each enemy type multiplies this by its own kill reward")
    f.field("Start gold", Controls.slider(0, 1000, 10, c.economy.startGold, v => eco(_.copy(startGold = v.toInt)), "gold"),
      "Used by levels that do not set their own start gold")

    f.section("Meta currency (paid when a run ends)")
    f.field("Per level cleared", Controls.stepper(0, 200, c.meta.payoutPerLevel, v => meta(_.copy(payoutPerLevel = v))))
    f.field("Per wave cleared", Controls.stepper(0, 50, c.meta.payoutPerWave, v => meta(_.copy(payoutPerWave = v))))
    f.field("Win bonus", Controls.stepper(0, 500, c.meta.payoutWinBonus, v => meta(_.copy(payoutWinBonus = v))))

    f.section("Draft (pick after each level)")
    f.field("Offers", Controls.stepper(1, 6, c.draft.offerCount, v => draft(_.copy(offerCount = v))))
    f.field("Reroll cost", Controls.slider(0, 200, 5, c.draft.rerollCost, v => draft(_.copy(rerollCost = v.toInt)), "gold"))
    f.field("  more per reroll", Controls.slider(0, 100, 5, c.draft.rerollCostGrowth, v => draft(_.copy(rerollCostGrowth = v.toInt)), "gold"))
    f.field("Rounds rolled ahead", Controls.stepper(1, 50, c.draft.poolRounds, v => draft(_.copy(poolRounds = v))),
      "How many rounds of offers a run rolls up front")

    f.section("Combat")
    f.field("Break between waves", Controls.slider(0, 30, 0.5, c.combat.breakTime, v => combat(_.copy(breakTime = v.toFloat)), "s"))
    f.field("Critical hit damage", Controls.slider(100, 500, 5, math.round(c.combat.critMult * 100), v =>
      combat(_.copy(critMult = (v / 100).toFloat)), "%"), "Damage of a critical hit before talents (150% = 1.5x)")
    f.field("Immune from", Controls.slider(50, 100, 1, math.round(c.combat.immuneAt * 100), v =>
      combat(_.copy(immuneAt = (v / 100).toFloat)), "% resistance"), "At or above this an enemy shows \"immune\"")
    f.field("Hits to study", Controls.stepper(1, 50, c.combat.studyHits, v => combat(_.copy(studyHits = v))),
      "Hits on a type before the journal records its exact resistance")

    f.section("Advanced")
    f.field("Hit radius", Controls.slider(0, 30, 0.5, c.combat.hitRadius, v => combat(_.copy(hitRadius = v.toFloat)), "px"),
      "How close a shot must get to land")
    f.field("Fast-forward", Controls.stepper(1, 10, c.combat.fastForward, v => combat(_.copy(fastForward = v))),
      "Simulation steps per frame at fast speed")
    f.field("Longest step", Controls.slider(0.01, 0.2, 0.01, c.combat.maxStepDt, v => combat(_.copy(maxStepDt = v.toFloat)), "s"),
      "Longest time one simulation step may cover")
    add(JScrollPane(f.finish()), BorderLayout.CENTER)
    revalidate()
    repaint()
