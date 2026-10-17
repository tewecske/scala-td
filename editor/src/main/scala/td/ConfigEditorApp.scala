package td

import com.badlogic.gdx.graphics.Color
import java.awt.{BorderLayout, Font, GridLayout}
import javax.swing.*
import scala.util.Try

object ConfigEditorApp:
  def main(args: Array[String]): Unit =
    SwingUtilities.invokeLater(() => new ConfigEditorFrame().setVisible(true))

// -- field widgets -------------------------------------------------------------

sealed trait Field:
  def label: String
  def component: JComponent

final class IntField(val label: String, min: Int, max: Int, initial: Int) extends Field:
  private val spinner = JSpinner(SpinnerNumberModel(initial, min, max, 1))
  val component: JComponent = spinner
  def read: Either[String, Int] = Right(spinner.getValue.asInstanceOf[Integer].intValue)

final class FloatField(val label: String, min: Float, max: Float, initial: Float) extends Field:
  private val text = JTextField(initial.toString, 12)
  val component: JComponent = text
  def read: Either[String, Float] =
    text.getText.trim.toFloatOption match
      case None                        => Left(s"'$label' is not a number")
      case Some(v) if v < min || v > max => Left(s"'$label' must be in [$min,$max]")
      case Some(v)                      => Right(v)

final class StringField(val label: String, initial: String) extends Field:
  private val text = JTextField(initial, 16)
  val component: JComponent = text
  def read: Either[String, String] = Right(text.getText)

final class BoolField(val label: String, initial: Boolean) extends Field:
  private val box = JCheckBox()
  box.setSelected(initial)
  val component: JComponent = box
  def read: Either[String, Boolean] = Right(box.isSelected)

final class EnumField(val label: String, values: Vector[String], initial: String) extends Field:
  private val combo = JComboBox(values.toArray)
  combo.setSelectedItem(initial)
  val component: JComponent = combo
  def read: Either[String, String] = Right(combo.getSelectedItem.asInstanceOf[String])

final class ColorField(val label: String, initial: Color) extends Field:
  private val text = JTextField(ConfigWriter.colorHex(initial), 9)
  val component: JComponent = text
  def read: Either[String, Color] =
    Try(ConfigLoader.parseColor(text.getText.trim)).toOption.toRight(s"'$label' bad color")

final class CompositionField(val label: String, initial: Vector[(String, Int)]) extends Field:
  private val text = JTextField(ConfigCodec.formatComposition(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[(String, Int)]] = ConfigCodec.parseComposition(text.getText)

final class WaypointsField(val label: String, initial: Vector[GridPos]) extends Field:
  private val text = JTextField(ConfigCodec.formatWaypoints(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[GridPos]] = ConfigCodec.parseWaypoints(text.getText)

final class NamesField(val label: String, initial: Vector[String]) extends Field:
  private val text = JTextField(ConfigCodec.formatNames(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[String]] = Right(ConfigCodec.parseNames(text.getText))

final class BranchesField(val label: String, initial: Vector[Branch]) extends Field:
  private val text = JTextField(ConfigCodec.formatBranches(initial), 20)
  val component: JComponent = text
  def read: Either[String, Vector[Branch]] = ConfigCodec.parseBranches(text.getText)

// -- frame ---------------------------------------------------------------------

final class ConfigEditorFrame extends JFrame:
  private val status = JLabel(" ")

  private var boardFs: Vector[Field]                 = Vector.empty
  private var economyFs: Vector[Field]               = Vector.empty
  private var metaFs: Vector[Field]                  = Vector.empty
  private var draftFs: Vector[Field]                 = Vector.empty
  private var combatFs: Vector[Field]                = Vector.empty
  private var i18nFs: Vector[Field]                  = Vector.empty
  private var towersFs: Vector[Vector[Field]]        = Vector.empty
  private var enemiesFs: Vector[Vector[Field]]       = Vector.empty
  private var levelsFs: Vector[(Vector[Field], Int)] = Vector.empty
  private var colorsFs: Vector[Field]                = Vector.empty
  private var lightingFs: Vector[Field]              = Vector.empty
  private var effectsFs: Vector[Field]               = Vector.empty
  private var biomesFs: Vector[(Vector[Field], Vector[String])] = Vector.empty
  private var twistsFs: Vector[(Vector[Field], Vector[String])] = Vector.empty
  private var relicsFs: Vector[(Vector[Field], Vector[String])] = Vector.empty
  private var augmentsFs: Vector[(Vector[Field], Vector[String])] = Vector.empty
  private var talentsFs: Vector[(Vector[Field], Vector[String])] = Vector.empty
  private var loadoutCfg: LoadoutConfig = Config.Loadout
  private var loadedEnemies: Vector[EnemySpec] = Vector.empty
  private var loadedBiomes: Vector[Biome] = Vector.empty

  setTitle("Config Editor")
  setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
  setSize(1000, 720)
  setLocationRelativeTo(null)

  loadConfig() match
    case Right(cfg) => buildWith(cfg)
    case Left(err) =>
      JOptionPane.showMessageDialog(this, err, "Config Editor", JOptionPane.ERROR_MESSAGE)
      throw RuntimeException(err)

  // -- loading ----------------------------------------------------------------

  private def loadConfig(): Either[String, GameConfig] =
    ConfigLoader.load() match
      case Right(cfg) => Right(cfg)
      case Left(err) =>
        JOptionPane.showMessageDialog(this, "cwd config broken:\n" + err, "Config Editor", JOptionPane.WARNING_MESSAGE)
        ConfigLoader.bundled()

  /** The form does not edit boss, death-spawn or promotion yet; keep them from the loaded file. */
  private def keepUneditedFields(e: EnemySpec): EnemySpec =
    loadedEnemies.find(_.name == e.name)
      .map(o => e.copy(boss = o.boss, onDeath = o.onDeath, promotesTo = o.promotesTo, leakCost = o.leakCost))
      .getOrElse(e)

  /** Biome fields the form doesn't show (board, levels, colors) come from the loaded config. */
  private def keepUneditedBiomeFields(b: Biome): Biome =
    loadedBiomes.find(_.id == b.id)
      .map(o => b.copy(gridSize = o.gridSize, levels = o.levels, background = o.background, path = o.path,
        build = o.build, gridLine = o.gridLine))
      .getOrElse(b)

  private def buildWith(cfg: GameConfig): Unit =
    boardFs   = buildBoard(cfg.board)
    economyFs = buildEconomy(cfg.economy)
    metaFs    = buildMeta(cfg.meta)
    draftFs   = buildDraft(cfg.draft)
    combatFs  = buildCombat(cfg.combat)
    i18nFs    = buildI18n(cfg.i18n)
    towersFs  = cfg.towers.map(buildTower)
    enemiesFs = cfg.enemies.map(buildEnemy)
    loadedEnemies = cfg.enemies
    loadedBiomes = cfg.biomes
    levelsFs  = cfg.levels.map(l => (buildLevel(l), l.waves.size))
    colorsFs  = buildColors(cfg.colors)
    lightingFs = buildLighting(cfg.lighting)
    effectsFs  = buildEffects(cfg.effects)
    biomesFs  = cfg.biomes.map(b => (buildBiome(b), b.environment.map(_.params.keys.toVector.sorted).getOrElse(Vector.empty)))
    twistsFs  = cfg.twists.map(t => (buildTwist(t), t.effect.params.keys.toVector.sorted))
    relicsFs  = cfg.relics.map(r => (buildRelic(r), r.effect.params.keys.toVector.sorted))
    augmentsFs = cfg.augments.map(a => (buildAugment(a), a.effect.params.keys.toVector.sorted))
    talentsFs = cfg.talents.map(t => (buildTalent(t), t.effect.params.keys.toVector.sorted))
    loadoutCfg = cfg.loadout

    val tabs = JTabbedPane()
    tabs.addTab("Board", sectionPanel(boardFs))
    tabs.addTab("Economy", sectionPanel(economyFs))
    tabs.addTab("Meta", sectionPanel(metaFs))
    tabs.addTab("Draft", sectionPanel(draftFs))
    tabs.addTab("Combat", sectionPanel(combatFs))
    tabs.addTab("I18n", sectionPanel(i18nFs))
    tabs.addTab("Towers", collectionPanel(cfg.towers.map(_.name), towersFs))
    tabs.addTab("Enemies", collectionPanel(cfg.enemies.map(_.name), enemiesFs))
    tabs.addTab("Levels", collectionPanel(cfg.levels.map(_.name), levelsFs.map(_._1)))
    tabs.addTab("Colors", sectionPanel(colorsFs))
    tabs.addTab("Lighting", sectionPanel(lightingFs))
    tabs.addTab("Effects", sectionPanel(effectsFs))
    tabs.addTab("Biomes", collectionPanel(cfg.biomes.map(b => s"${b.id} (${b.name})"), biomesFs.map(_._1)))
    tabs.addTab("Twists", collectionPanel(cfg.twists.map(t => s"${t.id} (${t.name})"), twistsFs.map(_._1)))
    tabs.addTab("Relics", collectionPanel(cfg.relics.map(r => s"${r.id} (${r.name})"), relicsFs.map(_._1)))
    tabs.addTab("Augments", collectionPanel(cfg.augments.map(a => s"${a.id} (${a.name})"), augmentsFs.map(_._1)))
    tabs.addTab("Talents", collectionPanel(cfg.talents.map(t => s"${t.id} (${t.name})"), talentsFs.map(_._1)))

    val content = JPanel(BorderLayout())
    content.add(tabs, BorderLayout.CENTER)
    content.add(bottomBar(), BorderLayout.SOUTH)
    setContentPane(content)
    revalidate()

  private def bottomBar(): JComponent =
    val bar = JPanel(BorderLayout())
    val buttons = JPanel()
    val reload = JButton("Reload")
    reload.addActionListener(_ => onReload())
    val save = JButton("Save")
    save.addActionListener(_ => onSave())
    buttons.add(reload)
    buttons.add(save)
    bar.add(buttons, BorderLayout.WEST)
    bar.add(status, BorderLayout.CENTER)
    bar

  private def onReload(): Unit =
    loadConfig() match
      case Right(cfg) =>
        buildWith(cfg)
        status.setText("Reloaded")
      case Left(err) =>
        JOptionPane.showMessageDialog(this, err, "Config Editor", JOptionPane.ERROR_MESSAGE)

  // -- save -------------------------------------------------------------------

  private def onSave(): Unit =
    readAll() match
      case Left(err) =>
        JOptionPane.showMessageDialog(this, err, "Config Editor", JOptionPane.ERROR_MESSAGE)
      case Right(cfg) =>
        ConfigLoader.parse(ConfigWriter.toJson(cfg)) match
          case Left(err) =>
            JOptionPane.showMessageDialog(this, "Invalid config: " + err, "Config Editor", JOptionPane.ERROR_MESSAGE)
          case Right(_) =>
            ConfigWriter.write(cfg, ConfigLoader.FileName) match
              case Left(err) => JOptionPane.showMessageDialog(this, err, "Config Editor", JOptionPane.ERROR_MESSAGE)
              case Right(_)  => status.setText("Saved " + ConfigLoader.FileName)

  private def readAll(): Either[String, GameConfig] =
    for
      board   <- readBoard(boardFs)
      economy <- readEconomy(economyFs)
      meta    <- readMeta(metaFs)
      draft   <- readDraft(draftFs)
      combat  <- readCombat(combatFs)
      i18n    <- readI18n(i18nFs)
      towers  <- sequence(towersFs.map(readTower))
      enemies <- sequence(enemiesFs.map(readEnemy)).map(_.map(keepUneditedFields))
      levels  <- sequence(levelsFs.map { (fs, wc) => readLevel(fs, wc) })
       colors  <- readColors(colorsFs)
      lighting <- readLighting(lightingFs)
      effects  <- readEffects(effectsFs)
      biomes  <- sequence(biomesFs.map { (fs, keys) => readBiome(fs, keys) }).map(_.map(keepUneditedBiomeFields))
      twists  <- sequence(twistsFs.map { (fs, keys) => readTwist(fs, keys) })
      relics  <- sequence(relicsFs.map { (fs, keys) => readRelic(fs, keys) })
      augments <- sequence(augmentsFs.map { (fs, keys) => readAugment(fs, keys) })
      talents <- sequence(talentsFs.map { (fs, keys) => readTalent(fs, keys) })
    yield GameConfig(board, economy, meta, combat, draft, i18n, towers, enemies,
                      levels, loadoutCfg, colors, lighting, effects, biomes, twists, relics, augments, talents)

  private def sequence[A](xs: Vector[Either[String, A]]): Either[String, Vector[A]] =
    xs.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) {
      (acc, e) =>
        for
          as <- acc
          a  <- e
        yield as :+ a
    }

  // -- layout helpers ---------------------------------------------------------

  private def gridPanel(fs: Vector[Field]): JComponent =
    val p = JPanel(GridLayout(0, 2))
    fs.foreach { f =>
      p.add(JLabel(f.label))
      p.add(f.component)
    }
    p

  private def sectionPanel(fs: Vector[Field]): JComponent =
    JScrollPane(gridPanel(fs))

  private def collectionPanel(titles: Vector[String], groups: Vector[Vector[Field]]): JComponent =
    val inner = JPanel()
    inner.setLayout(BoxLayout(inner, BoxLayout.Y_AXIS))
    titles.zip(groups).foreach { (title, fs) =>
      val head = JLabel(title)
      head.setFont(head.getFont.deriveFont(Font.BOLD))
      val outer = JPanel(BorderLayout())
      outer.add(head, BorderLayout.NORTH)
      outer.add(gridPanel(fs), BorderLayout.CENTER)
      inner.add(outer)
    }
    JScrollPane(inner)

  // -- scalar sections --------------------------------------------------------

  private def buildBoard(b: BoardConfig): Vector[Field] = Vector(
    IntField("gridSize", 1, Int.MaxValue, b.gridSize),
    FloatField("boardPx", 100f, 10000f, b.boardPx),
    FloatField("panelPx", 0f, 10000f, b.panelPx)
  )

  private def readBoard(fs: Vector[Field]): Either[String, BoardConfig] =
    for
      g <- fs(0).asInstanceOf[IntField].read
      c <- fs(1).asInstanceOf[FloatField].read
      p <- fs(2).asInstanceOf[FloatField].read
    yield BoardConfig(g, c, p)

  private def buildEconomy(e: EconomyConfig): Vector[Field] = Vector(
    IntField("startGold", 0, Int.MaxValue, e.startGold),
    IntField("startLives", 1, Int.MaxValue, e.startLives),
    IntField("killReward", 0, Int.MaxValue, e.killReward)
  )

  private def readEconomy(fs: Vector[Field]): Either[String, EconomyConfig] =
    for
      g  <- fs(0).asInstanceOf[IntField].read
      l  <- fs(1).asInstanceOf[IntField].read
      kr <- fs(2).asInstanceOf[IntField].read
    yield EconomyConfig(g, l, kr)

  private def buildMeta(m: MetaConfig): Vector[Field] = Vector(
    IntField("payoutPerLevel", 0, Int.MaxValue, m.payoutPerLevel),
    IntField("payoutPerWave", 0, Int.MaxValue, m.payoutPerWave),
    IntField("payoutWinBonus", 0, Int.MaxValue, m.payoutWinBonus)
  )

  private def readMeta(fs: Vector[Field]): Either[String, MetaConfig] =
    for
      a <- fs(0).asInstanceOf[IntField].read
      b <- fs(1).asInstanceOf[IntField].read
      c <- fs(2).asInstanceOf[IntField].read
    yield MetaConfig(a, b, c)

  private def buildDraft(d: DraftConfig): Vector[Field] = Vector(
    IntField("offerCount", 1, Int.MaxValue, d.offerCount),
    IntField("rerollCost", 0, Int.MaxValue, d.rerollCost),
    IntField("rerollCostGrowth", 0, Int.MaxValue, d.rerollCostGrowth),
    IntField("poolRounds", 1, Int.MaxValue, d.poolRounds)
  )

  private def readDraft(fs: Vector[Field]): Either[String, DraftConfig] =
    for
      o <- fs(0).asInstanceOf[IntField].read
      r <- fs(1).asInstanceOf[IntField].read
      g <- fs(2).asInstanceOf[IntField].read
      p <- fs(3).asInstanceOf[IntField].read
    yield DraftConfig(o, r, g, p)

  private def buildCombat(c: CombatConfig): Vector[Field] = Vector(
    FloatField("maxStepDt", 0.0001f, 1000f, c.maxStepDt),
    IntField("fastForward", 1, Int.MaxValue, c.fastForward),
    FloatField("hitRadius", 0f, 100000f, c.hitRadius),
    FloatField("breakTime", 0f, 100000f, c.breakTime),
    IntField("studyHits", 0, Int.MaxValue, c.studyHits),
    FloatField("critMult", 0f, 100f, c.critMult),
    FloatField("immuneAt", 0f, 1f, c.immuneAt)
  )

  private def readCombat(fs: Vector[Field]): Either[String, CombatConfig] =
    for
      ms <- fs(0).asInstanceOf[FloatField].read
      ff <- fs(1).asInstanceOf[IntField].read
      hr <- fs(2).asInstanceOf[FloatField].read
      bt <- fs(3).asInstanceOf[FloatField].read
      sh <- fs(4).asInstanceOf[IntField].read
      cm <- fs(5).asInstanceOf[FloatField].read
      im <- fs(6).asInstanceOf[FloatField].read
    yield CombatConfig(ms, ff, hr, bt, sh, cm, im)

  private def buildI18n(i: I18nConfig): Vector[Field] = Vector(
    StringField("lang", i.lang),
    BoolField("showCodes", i.showCodes)
  )

  private def readI18n(fs: Vector[Field]): Either[String, I18nConfig] =
    for
      lang <- fs(0).asInstanceOf[StringField].read
      show <- fs(1).asInstanceOf[BoolField].read
    yield I18nConfig(lang, show)

  // -- towers -----------------------------------------------------------------

  private def buildTower(t: TowerSpec): Vector[Field] = Vector(
    StringField("name", t.name),
    IntField("cost", 0, Int.MaxValue, t.cost),
    ColorField("color", t.color),
    FloatField("range (cells)", 0f, 1000f, t.range),
    FloatField("fireInterval", 0.0001f, 1000f, t.fireInterval),
    FloatField("damage", 0f, 100000f, t.damage),
    FloatField("projectileSpeed", 0f, 100000f, t.projectileSpeed),
    EnumField("kind", DamageKind.values.map(_.toString).toVector, t.kind.toString),
    FloatField("splashRadius (cells)", 0f, 1000f, t.splashRadius)
  )

  private def readTower(fs: Vector[Field]): Either[String, TowerSpec] =
    for
      name <- fs(0).asInstanceOf[StringField].read
      cost <- fs(1).asInstanceOf[IntField].read
      col  <- fs(2).asInstanceOf[ColorField].read
      rng  <- fs(3).asInstanceOf[FloatField].read
      fi   <- fs(4).asInstanceOf[FloatField].read
      dmg  <- fs(5).asInstanceOf[FloatField].read
      ps   <- fs(6).asInstanceOf[FloatField].read
      kind <- fs(7).asInstanceOf[EnumField].read
      spl  <- fs(8).asInstanceOf[FloatField].read
    yield TowerSpec(name, cost, col, rng, fi, dmg, ps,
                    DamageKind.values.find(_.toString == kind).get, spl)

  // -- enemies ----------------------------------------------------------------

  private val NoneName = "None"

  private def buildEnemy(e: EnemySpec): Vector[Field] = Vector(
    StringField("name", e.name),
    FloatField("baseHp", 0f, 10000000f, e.baseHp),
    FloatField("baseSpeed", 0f, 100000f, e.baseSpeed),
    ColorField("color", e.color),
    FloatField("size", 0f, 1000f, e.size),
    FloatField("resist.kinetic", 0f, 1f, e.resist.kinetic),
    FloatField("resist.fire", 0f, 1f, e.resist.fire),
    FloatField("resist.slow", 0f, 1f, e.resist.slow),
    FloatField("resist.splash", 0f, 1f, e.resist.splash),
    FloatField("resist.explosion", 0f, 1f, e.resist.explosion),
    EnumField("aura.kind", NoneName +: AuraKind.values.map(_.toString).toVector,
              e.aura.map(_.kind.toString).getOrElse(NoneName)),
    FloatField("aura.radius (cells)", 0f, 1000f, e.aura.map(_.radius).getOrElse(0f)),
    FloatField("aura.magnitude", 0f, 100f, e.aura.map(_.magnitude).getOrElse(0f)),
    BoolField("heavy", e.heavy),
    EnumField("sidePreference", NoneName +: RouteSide.values.map(_.toString).toVector,
              e.sidePreference.map(_.toString).getOrElse(NoneName))
  )

  private def readEnemy(fs: Vector[Field]): Either[String, EnemySpec] =
    for
      name  <- fs(0).asInstanceOf[StringField].read
      hp    <- fs(1).asInstanceOf[FloatField].read
      spd   <- fs(2).asInstanceOf[FloatField].read
      col   <- fs(3).asInstanceOf[ColorField].read
      size  <- fs(4).asInstanceOf[FloatField].read
      k     <- fs(5).asInstanceOf[FloatField].read
      f     <- fs(6).asInstanceOf[FloatField].read
      sl    <- fs(7).asInstanceOf[FloatField].read
      sp    <- fs(8).asInstanceOf[FloatField].read
      ex    <- fs(9).asInstanceOf[FloatField].read
      akind <- fs(10).asInstanceOf[EnumField].read
      arad  <- fs(11).asInstanceOf[FloatField].read
      amag  <- fs(12).asInstanceOf[FloatField].read
      heavy <- fs(13).asInstanceOf[BoolField].read
      side  <- fs(14).asInstanceOf[EnumField].read
    yield
      val aura =
        if akind == NoneName then None
        else Some(Aura(AuraKind.values.find(_.toString == akind).get, arad, amag))
      val sidePref =
        if side == NoneName then None
        else Some(RouteSide.values.find(_.toString == side).get)
      EnemySpec(name, hp, spd, col, size, Resistances(k, f, sl, sp, ex), aura, heavy, sidePref)

  // -- levels -----------------------------------------------------------------

  private def buildLevel(l: LevelConfig): Vector[Field] =
    Vector[Field](
      StringField("name", l.name),
      WaypointsField("route.waypoints", l.route.waypoints),
      BranchesField("route.branches", l.route.branches)
    ) ++ l.waves.flatMap(w =>
      Vector[Field](
        IntField("wave.count", 0, Int.MaxValue, w.count),
        FloatField("wave.spawnInterval", 0f, 100000f, w.spawnInterval),
        FloatField("wave.hpScale", 0f, 100000f, w.hpScale),
        CompositionField("wave.composition", w.composition)
      )
    )

  private def readLevel(fs: Vector[Field], waveCount: Int): Either[String, LevelConfig] =
    for
      name  <- fs(0).asInstanceOf[StringField].read
      wps   <- fs(1).asInstanceOf[WaypointsField].read
      brs   <- fs(2).asInstanceOf[BranchesField].read
      waves <- sequence(fs.drop(3).grouped(4).toVector.take(waveCount).map { g =>
        for
          c  <- g(0).asInstanceOf[IntField].read
          si <- g(1).asInstanceOf[FloatField].read
          hs <- g(2).asInstanceOf[FloatField].read
          cp <- g(3).asInstanceOf[CompositionField].read
        yield WaveSpec(c, si, hs, cp)
      })
    yield LevelConfig(name, waves, Route(wps, brs))

  // -- colors -----------------------------------------------------------------

  private def buildColors(c: Colors): Vector[Field] = Vector(
    ColorField("background", c.background),
    ColorField("path", c.path),
    ColorField("build", c.build),
    ColorField("gridLine", c.gridLine),
    ColorField("enemy", c.enemy),
    ColorField("shot", c.shot),
    ColorField("shotFire", c.shotFire),
    ColorField("shotFrost", c.shotFrost),
    ColorField("shotSplash", c.shotSplash),
    ColorField("shotBoom", c.shotBoom),
    ColorField("burnTint", c.burnTint),
    ColorField("slowTint", c.slowTint),
    ColorField("buffTint", c.buffTint),
    ColorField("panel", c.panel),
    ColorField("hoverOk", c.hoverOk),
    ColorField("hoverBad", c.hoverBad),
    ColorField("range", c.range),
    ColorField("buttonFill", c.buttonFill),
    ColorField("hpBarBack", c.hpBarBack),
    ColorField("hpBarFill", c.hpBarFill),
    ColorField("text", c.text),
    ColorField("dimText", c.dimText)
  )

  private def readColors(fs: Vector[Field]): Either[String, Colors] =
    for
      c0  <- fs(0).asInstanceOf[ColorField].read
      c1  <- fs(1).asInstanceOf[ColorField].read
      c2  <- fs(2).asInstanceOf[ColorField].read
      c3  <- fs(3).asInstanceOf[ColorField].read
      c4  <- fs(4).asInstanceOf[ColorField].read
      c5  <- fs(5).asInstanceOf[ColorField].read
      c6  <- fs(6).asInstanceOf[ColorField].read
      c7  <- fs(7).asInstanceOf[ColorField].read
      c8  <- fs(8).asInstanceOf[ColorField].read
      c9  <- fs(9).asInstanceOf[ColorField].read
      c10 <- fs(10).asInstanceOf[ColorField].read
      c11 <- fs(11).asInstanceOf[ColorField].read
      c12 <- fs(12).asInstanceOf[ColorField].read
      c13 <- fs(13).asInstanceOf[ColorField].read
      c14 <- fs(14).asInstanceOf[ColorField].read
      c15 <- fs(15).asInstanceOf[ColorField].read
      c16 <- fs(16).asInstanceOf[ColorField].read
      c17 <- fs(17).asInstanceOf[ColorField].read
      c18 <- fs(18).asInstanceOf[ColorField].read
      c19 <- fs(19).asInstanceOf[ColorField].read
      c20 <- fs(20).asInstanceOf[ColorField].read
      c21 <- fs(21).asInstanceOf[ColorField].read
    yield Colors(c0, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13,
                 c14, c15, c16, c17, c18, c19, c20, c21)

  // -- lighting ---------------------------------------------------------------

  private def buildLighting(l: LightingConfig): Vector[Field] = Vector(
    ColorField("ambient", l.ambient),
    FloatField("ambientIntensity", 0f, 2f, l.ambientIntensity)
  )

  private def readLighting(fs: Vector[Field]): Either[String, LightingConfig] =
    for
      a   <- fs(0).asInstanceOf[ColorField].read
      ai  <- fs(1).asInstanceOf[FloatField].read
    yield LightingConfig(a, ai)

  // -- effects ----------------------------------------------------------------

  private def buildEffects(e: EffectsConfig): Vector[Field] = Vector(
    ColorField("vignette.color", e.vignette.color),
    FloatField("vignette.strength", 0f, 1f, e.vignette.strength),
    FloatField("vignette.radius", 0f, 1f, e.vignette.radius),
    ColorField("sparks.color", e.sparks.color),
    FloatField("sparks.life", 0f, 5f, e.sparks.life),
    FloatField("sparks.speed", 0f, 1000f, e.sparks.speed),
    FloatField("sparks.size", 0f, 50f, e.sparks.size),
    IntField("sparks.count", 0, 50, e.sparks.count),
    ColorField("shadow.color", e.shadow.color),
    FloatField("shadow.alpha", 0f, 1f, e.shadow.alpha),
    FloatField("shadow.offsetX", -50f, 50f, e.shadow.offsetX),
    FloatField("shadow.offsetY", -50f, 50f, e.shadow.offsetY),
    FloatField("shadow.scale", 0f, 4f, e.shadow.scale),
    FloatField("shake.magnitude", 0f, 50f, e.shake.magnitude),
    FloatField("shake.duration", 0f, 5f, e.shake.duration)
  )

  private def readEffects(fs: Vector[Field]): Either[String, EffectsConfig] =
    for
      vc  <- fs(0).asInstanceOf[ColorField].read
      vs  <- fs(1).asInstanceOf[FloatField].read
      vr  <- fs(2).asInstanceOf[FloatField].read
      sc  <- fs(3).asInstanceOf[ColorField].read
      sl  <- fs(4).asInstanceOf[FloatField].read
      ss  <- fs(5).asInstanceOf[FloatField].read
      sz  <- fs(6).asInstanceOf[FloatField].read
      sn  <- fs(7).asInstanceOf[IntField].read
      shc <- fs(8).asInstanceOf[ColorField].read
      sha <- fs(9).asInstanceOf[FloatField].read
      shx <- fs(10).asInstanceOf[FloatField].read
      shy <- fs(11).asInstanceOf[FloatField].read
      shs <- fs(12).asInstanceOf[FloatField].read
      m   <- fs(13).asInstanceOf[FloatField].read
      d   <- fs(14).asInstanceOf[FloatField].read
    yield EffectsConfig(
      VignetteConfig(vc, vs, vr),
      SparksConfig(sc, sl, ss, sz, sn),
      ShadowConfig(shc, sha, shx, shy, shs),
      ShakeConfig(m, d)
    )

  // -- effect-bearing collections ---------------------------------------------

  private def buildEffectParams(prefix: String, e: Effect): Vector[Field] =
    e.params.toVector.sortBy(_._1).map { (k, v) =>
      FloatField(s"$prefix.$k", -100000f, 100000f, v)
    }

  private def readParams(keys: Vector[String], fs: Vector[Field]): Either[String, Map[String, Float]] =
    sequence(keys.zip(fs).map { (k, f) =>
      f.asInstanceOf[FloatField].read.map(k -> _)
    }).map(_.toMap)

  private def buildBiome(b: Biome): Vector[Field] =
    Vector[Field](
      StringField("id", b.id),
      StringField("name", b.name),
      NamesField("roster", b.roster),
      StringField("environment.kind", b.environment.map(_.kind).getOrElse(NoneName))
    ) ++
      b.environment.map(buildEffectParams("environment", _)).getOrElse(Vector.empty) ++
      Vector[Field](
        EnumField("resistTheme.primary", DamageKind.values.map(_.toString).toVector,
                  b.resistTheme.primary.toString),
        FloatField("resistTheme.primaryAmount", 0f, 1f, b.resistTheme.primaryAmount),
        EnumField("resistTheme.secondary", DamageKind.values.map(_.toString).toVector,
                  b.resistTheme.secondary.toString),
        FloatField("resistTheme.secondaryAmount", 0f, 1f, b.resistTheme.secondaryAmount),
        BoolField("starter", b.starter)
      )

  private def readBiome(fs: Vector[Field], envKeys: Vector[String]): Either[String, Biome] =
    val envKind = fs(3).asInstanceOf[StringField]
    val envFs   = fs.slice(4, 4 + envKeys.size)
    val resist  = 4 + envKeys.size
    for
      id     <- fs(0).asInstanceOf[StringField].read
      name   <- fs(1).asInstanceOf[StringField].read
      roster <- fs(2).asInstanceOf[NamesField].read
      kind   <- envKind.read
      params <- readParams(envKeys, envFs)
      pri    <- fs(resist).asInstanceOf[EnumField].read
      priAmt <- fs(resist + 1).asInstanceOf[FloatField].read
      sec    <- fs(resist + 2).asInstanceOf[EnumField].read
      secAmt <- fs(resist + 3).asInstanceOf[FloatField].read
      starter <- fs(resist + 4).asInstanceOf[BoolField].read
    yield
      val env =
        if kind.trim.isEmpty || kind.trim == NoneName then None
        else Some(Effect(kind, params))
      Biome(id, name, roster, env, ResistTheme(
        DamageKind.values.find(_.toString == pri).get, priAmt,
        DamageKind.values.find(_.toString == sec).get, secAmt), starter = starter)

  private def buildTwist(t: Twist): Vector[Field] =
    Vector[Field](
      StringField("id", t.id),
      StringField("name", t.name),
      StringField("targetTower", t.targetTower),
      StringField("effect.kind", t.effect.kind)
    ) ++ buildEffectParams("effect", t.effect)

  private def readTwist(fs: Vector[Field], keys: Vector[String]): Either[String, Twist] =
    for
      id     <- fs(0).asInstanceOf[StringField].read
      name   <- fs(1).asInstanceOf[StringField].read
      target <- fs(2).asInstanceOf[StringField].read
      kind   <- fs(3).asInstanceOf[StringField].read
      params <- readParams(keys, fs.drop(4))
    yield Twist(id, name, target, Effect(kind, params))

  private def buildRelic(r: Relic): Vector[Field] =
    Vector[Field](
      StringField("id", r.id),
      StringField("name", r.name),
      EnumField("rarity", Rarity.values.map(_.toString).toVector, r.rarity.toString),
      StringField("requiredTalent", r.requiredTalent.getOrElse(NoneName)),
      StringField("effect.kind", r.effect.kind)
    ) ++ buildEffectParams("effect", r.effect)

  private def readRelic(fs: Vector[Field], keys: Vector[String]): Either[String, Relic] =
    for
      id     <- fs(0).asInstanceOf[StringField].read
      name   <- fs(1).asInstanceOf[StringField].read
      rarity <- fs(2).asInstanceOf[EnumField].read
      req    <- fs(3).asInstanceOf[StringField].read
      kind   <- fs(4).asInstanceOf[StringField].read
      params <- readParams(keys, fs.drop(5))
    yield
      val required = if req.trim.isEmpty || req.trim == NoneName then None else Some(req.trim)
      Relic(id, name, Rarity.values.find(_.toString == rarity).get, Effect(kind, params), required)

  private def buildAugment(a: Augment): Vector[Field] =
    Vector[Field](
      StringField("id", a.id),
      StringField("name", a.name),
      StringField("effect.kind", a.effect.kind)
    ) ++ buildEffectParams("effect", a.effect)

  private def readAugment(fs: Vector[Field], keys: Vector[String]): Either[String, Augment] =
    for
      id     <- fs(0).asInstanceOf[StringField].read
      name   <- fs(1).asInstanceOf[StringField].read
      kind   <- fs(2).asInstanceOf[StringField].read
      params <- readParams(keys, fs.drop(3))
    yield Augment(id, name, Effect(kind, params))

  private def buildTalent(t: Talent): Vector[Field] =
    Vector[Field](
      StringField("id", t.id),
      StringField("name", t.name),
      EnumField("layer", TalentLayer.values.map(_.toString).toVector, t.layer.toString),
      IntField("cost", 0, Int.MaxValue, t.cost),
      NamesField("prereqs", t.prereqs),
      StringField("effect.kind", t.effect.kind)
    ) ++ buildEffectParams("effect", t.effect)

  private def readTalent(fs: Vector[Field], keys: Vector[String]): Either[String, Talent] =
    for
      id      <- fs(0).asInstanceOf[StringField].read
      name    <- fs(1).asInstanceOf[StringField].read
      layer   <- fs(2).asInstanceOf[EnumField].read
      cost    <- fs(3).asInstanceOf[IntField].read
      prereqs <- fs(4).asInstanceOf[NamesField].read
      kind    <- fs(5).asInstanceOf[StringField].read
      params  <- readParams(keys, fs.drop(6))
    yield Talent(id, name, TalentLayer.values.find(_.toString == layer).get, cost,
                 prereqs, Effect(kind, params))