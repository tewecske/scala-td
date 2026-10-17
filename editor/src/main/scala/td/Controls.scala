package td

import java.awt.{BorderLayout, Color as AwtColor, Dimension, FlowLayout, GridBagConstraints, GridBagLayout, Insets}
import java.awt.event.{FocusAdapter, FocusEvent}
import javax.swing.*
import javax.swing.border.TitledBorder

/** Small Swing controls for the editor. Each one shows a value and calls
  * `onChange` with the new value as soon as the user changes it.
  */
object Controls:

  /** A slider with a number box next to it; both stay in sync. Drag for a rough
    * value, type or use the box's arrows for an exact one.
    */
  def slider(min: Double, max: Double, step: Double, value: Double, onChange: Double => Unit,
      suffix: String = "", log: Boolean = false): JComponent =
    // `log` spreads the slider evenly over orders of magnitude (1, 10, 100, 1000),
    // so small values are as easy to set as large ones
    val ticks = if log then 1000 else math.round((max - min) / step).toInt
    def clamp(v: Double) = math.max(min, math.min(max, v))
    def snap(v: Double)  = BigDecimal(math.round(v / step) * step).setScale(6, BigDecimal.RoundingMode.HALF_UP).toDouble
    def toTick(v: Double) =
      if log then math.round(math.log(clamp(v) / min) / math.log(max / min) * ticks).toInt
      else math.round((clamp(v) - min) / step).toInt
    def fromTick(t: Int) =
      if log then snap(min * math.pow(max / min, t.toDouble / ticks))
      else BigDecimal(min + t * step).setScale(6, BigDecimal.RoundingMode.HALF_UP).toDouble
    val s = JSlider(0, ticks, toTick(value))
    s.setPreferredSize(Dimension(260, s.getPreferredSize.height))
    // the box accepts values beyond the slider's range, for the rare outlier
    val box = JSpinner(SpinnerNumberModel(value, -1e9, 1e9, step))
    val editor = JSpinner.NumberEditor(box, if step >= 1 then "0" else if step >= 0.1 then "0.0#" else "0.0##")
    box.setEditor(editor)
    box.setPreferredSize(Dimension(90, box.getPreferredSize.height))
    var syncing = false
    s.addChangeListener(_ =>
      if !syncing then
        syncing = true
        val v = fromTick(s.getValue)
        box.setValue(v)
        onChange(v)
        syncing = false)
    box.addChangeListener(_ =>
      if !syncing then
        syncing = true
        val v = box.getValue.asInstanceOf[Number].doubleValue
        s.setValue(toTick(v))
        onChange(v)
        syncing = false)
    row(s, box, JLabel(suffix))

  /** Whole numbers that change a little at a time: up/down arrows. */
  def stepper(min: Int, max: Int, value: Int, onChange: Int => Unit): JComponent =
    val sp = JSpinner(SpinnerNumberModel(math.max(min, math.min(max, value)), min, max, 1))
    sp.setPreferredSize(Dimension(80, sp.getPreferredSize.height))
    sp.addChangeListener(_ => onChange(sp.getValue.asInstanceOf[Number].intValue))
    row(sp)

  /** Text that is committed when the user presses Enter or leaves the field. */
  def text(value: String, onCommit: String => Unit, columns: Int = 18): JTextField =
    val t = JTextField(value, columns)
    var last = value
    def commit(): Unit =
      if t.getText != last then
        last = t.getText
        onCommit(t.getText)
    t.addActionListener(_ => commit())
    t.addFocusListener(new FocusAdapter:
      override def focusLost(e: FocusEvent): Unit = commit())
    t

  def dropdown[A](options: Vector[A], selected: A, onChange: A => Unit, show: A => String = (a: A) => a.toString): JComponent =
    val labels = options.map(show)
    val c = JComboBox[String](labels.toArray)
    c.setSelectedIndex(math.max(0, options.indexOf(selected)))
    c.addActionListener(_ => if c.getSelectedIndex >= 0 then onChange(options(c.getSelectedIndex)))
    row(c)

  def checkbox(value: Boolean, onChange: Boolean => Unit, label: String = ""): JComponent =
    val b = JCheckBox(label, value)
    b.addActionListener(_ => onChange(b.isSelected))
    row(b)

  /** A swatch button that opens a color chooser. */
  def color(value: com.badlogic.gdx.graphics.Color, onChange: com.badlogic.gdx.graphics.Color => Unit): JComponent =
    val b = JButton(ConfigWriter.colorHex(value))
    def paint(c: com.badlogic.gdx.graphics.Color): Unit =
      b.setBackground(toAwt(c))
      b.setForeground(if c.r * 0.3f + c.g * 0.59f + c.b * 0.11f > 0.55f then AwtColor.BLACK else AwtColor.WHITE)
      b.setText(ConfigWriter.colorHex(c))
    b.setOpaque(true)
    paint(value)
    b.addActionListener { _ =>
      val chosen = JColorChooser.showDialog(b, "Enemy color", b.getBackground)
      if chosen != null then
        val c = fromAwt(chosen)
        paint(c)
        onChange(c)
    }
    row(b)

  def button(label: String, tip: String = "")(action: => Unit): JButton =
    val b = JButton(label)
    if tip.nonEmpty then b.setToolTipText(tip)
    b.addActionListener(_ => action)
    b

  def row(cs: JComponent*): JPanel =
    val p = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
    cs.foreach(p.add)
    p

  def toAwt(c: com.badlogic.gdx.graphics.Color): AwtColor = AwtColor(c.r, c.g, c.b, c.a)
  def fromAwt(c: AwtColor): com.badlogic.gdx.graphics.Color =
    com.badlogic.gdx.graphics.Color(c.getRed / 255f, c.getGreen / 255f, c.getBlue / 255f, c.getAlpha / 255f)

/** A two-column form: labels on the left, controls on the right, in titled sections. */
final class Form extends JPanel(GridBagLayout()):
  private var row = 0
  setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8))

  def section(title: String): Unit =
    val l = JLabel(title)
    l.setFont(l.getFont.deriveFont(java.awt.Font.BOLD, l.getFont.getSize2D + 2f))
    val c = cons(0)
    c.gridwidth = 2
    c.insets = Insets(if row == 0 then 0 else 14, 0, 4, 0)
    add(l, c)
    row += 1

  def field(label: String, control: JComponent, tip: String = ""): Unit =
    val l = JLabel(label)
    if tip.nonEmpty then
      l.setToolTipText(tip)
      control.setToolTipText(tip)
    add(l, cons(0))
    val c = cons(1)
    c.weightx = 1
    add(control, c)
    row += 1

  def full(control: JComponent): Unit =
    val c = cons(0)
    c.gridwidth = 2
    c.weightx = 1
    c.fill = GridBagConstraints.HORIZONTAL
    add(control, c)
    row += 1

  /** Pushes everything to the top. */
  def finish(): Form =
    val c = cons(0)
    c.weighty = 1
    add(JPanel(), c)
    this

  private def cons(x: Int): GridBagConstraints =
    val c = GridBagConstraints()
    c.gridx = x
    c.gridy = row
    c.anchor = GridBagConstraints.WEST
    c.insets = Insets(2, 0, 2, 8)
    c

/** A panel with a titled line border. */
def titled(title: String, inner: JComponent): JPanel =
  val p = JPanel(BorderLayout())
  p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT,
    TitledBorder.TOP))
  p.add(inner, BorderLayout.CENTER)
  p
