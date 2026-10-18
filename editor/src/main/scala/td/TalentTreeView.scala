package td

import java.awt.{BasicStroke, Color as AwtColor, Dimension, Font, Graphics, Graphics2D, RenderingHints}
import java.awt.event.{MouseAdapter, MouseEvent}
import java.awt.geom.RoundRectangle2D
import javax.swing.{JComponent, ToolTipManager}

/** The talent tree drawn like the game's talent screen ([[TalentTree]]): columns
  * filler / core / deep, a line from each talent to the ones it unlocks.
  *
  * `owned` talents are filled green, ones whose prerequisites are all owned are
  * plain, the rest are greyed. The `selected` talent is outlined and its lines are
  * highlighted. Clicking a talent calls `onClick` with its id.
  */
final class TalentTreeView(
    talents: () => Vector[Talent],
    owned: () => Set[String],
    selected: () => Option[String],
    onClick: String => Unit,
    BoxW: Int = TalentTreeView.BoxW,
    ColGap: Int = TalentTreeView.ColGap
) extends JComponent:
  import TalentTreeView.{BoxH, RowGap, Pad, HeaderH, Background, Box, Locked, Owned, Selected, Line, Faint, Text, Muted, Gold}

  ToolTipManager.sharedInstance().registerComponent(this)
  addMouseListener(new MouseAdapter:
    override def mouseClicked(e: MouseEvent): Unit = nodeAt(e.getX, e.getY).foreach(n => onClick(n.talent.id)))

  override def getPreferredSize: Dimension =
    val rows = math.max(1, TalentTree.rows(talents()))
    Dimension(Pad * 2 + TalentTree.Columns * BoxW + (TalentTree.Columns - 1) * ColGap,
      Pad + HeaderH + rows * (BoxH + RowGap))

  override def getToolTipText(e: MouseEvent): String =
    nodeAt(e.getX, e.getY).fold(null) { n =>
      val t     = n.talent
      val needs = t.prereqs.flatMap(id => talents().find(_.id == id).map(_.name))
      s"<html><b>${t.name}</b> (${t.cost})<br>${TalentsTab.describe(t)}" +
        (if needs.nonEmpty then s"<br>needs ${needs.mkString(", ")}" else "") + "</html>"
    }

  private def x(col: Int): Int = Pad + col * (BoxW + ColGap)
  private def y(row: Int): Int = Pad + HeaderH + row * (BoxH + RowGap)

  private def nodeAt(px: Int, py: Int): Option[TalentTree.Node] =
    TalentTree.layout(talents()).find(n => px >= x(n.column) && px <= x(n.column) + BoxW &&
      py >= y(n.row) && py <= y(n.row) + BoxH)

  override def paintComponent(g0: Graphics): Unit =
    val g = g0.asInstanceOf[Graphics2D]
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setColor(Background)
    g.fillRect(0, 0, getWidth, getHeight)
    val all   = talents()
    val nodes = TalentTree.layout(all)
    val at    = nodes.map(n => n.talent.id -> n).toMap
    val have  = owned()
    val sel   = selected()

    g.setFont(g.getFont.deriveFont(Font.BOLD, 12f))
    g.setColor(Muted)
    Vector("FILLER", "CORE", "DEEP").zipWithIndex.foreach((h, c) => g.drawString(h, x(c) + 4, Pad + 14))

    // lines under the boxes: right edge of the prerequisite to the left edge of the
    // talent it unlocks, bending halfway across the gap between the columns
    TalentTree.edges(all).sortBy(e => sel.exists(s => e.from == s || e.to == s)).foreach { e =>
      (at.get(e.from), at.get(e.to)) match
        case (Some(a), Some(b)) =>
          val hot = sel.exists(s => e.from == s || e.to == s)
          g.setColor(if hot then Selected else if have(e.from) then Line else Faint)
          g.setStroke(BasicStroke(if hot then 3f else 2f))
          val ay = y(a.row) + BoxH / 2
          val by = y(b.row) + BoxH / 2
          if a.column == b.column then
            val lx = x(a.column) - 8
            g.drawPolyline(Array(x(a.column), lx, lx, x(b.column)), Array(ay, ay, by, by), 4)
          else
            val mid = x(b.column) - ColGap / 2
            g.drawPolyline(Array(x(a.column) + BoxW, mid, mid, x(b.column)), Array(ay, ay, by, by), 4)
        case _ =>
    }

    nodes.foreach { n =>
      val t   = n.talent
      val box = RoundRectangle2D.Float(x(n.column).toFloat, y(n.row).toFloat, BoxW.toFloat, BoxH.toFloat, 12f, 12f)
      val ok  = TalentTree.available(t, have)
      g.setColor(if have(t.id) then Owned else if ok then Box else Locked)
      g.fill(box)
      if sel.contains(t.id) then
        g.setColor(Selected)
        g.setStroke(BasicStroke(3f))
        g.draw(box)
      g.setColor(if have(t.id) || ok then Text else Muted)
      g.setFont(g.getFont.deriveFont(Font.BOLD, 12f))
      g.drawString(clip(g, t.name, BoxW - 34), x(n.column) + 10, y(n.row) + 15)
      g.setFont(g.getFont.deriveFont(Font.PLAIN, 11f))
      g.drawString(clip(g, TalentsTab.describe(t), BoxW - 16), x(n.column) + 10, y(n.row) + 30)
      g.setColor(Gold)
      g.setFont(g.getFont.deriveFont(Font.BOLD, 12f))
      val cost = t.cost.toString
      g.drawString(cost, x(n.column) + BoxW - 10 - g.getFontMetrics.stringWidth(cost), y(n.row) + 15)
    }

  private def clip(g: Graphics2D, s: String, w: Int): String =
    val fm = g.getFontMetrics
    if fm.stringWidth(s) <= w then s
    else Iterator.from(s.length - 1, -1).map(n => s.take(n) + "…").find(c => fm.stringWidth(c) <= w).getOrElse("…")

object TalentTreeView:
  val BoxW    = 190
  val BoxH    = 38
  val ColGap  = 56
  val RowGap  = 8
  val Pad     = 10
  val HeaderH = 22

  // the game's talent screen colors
  private val Background = AwtColor(8, 30, 10)
  private val Box        = AwtColor(52, 56, 128)
  private val Locked     = AwtColor(26, 28, 62)
  private val Owned      = AwtColor(38, 92, 52)
  private val Selected   = AwtColor(255, 215, 90)
  private val Line       = AwtColor(200, 205, 225)
  private val Faint      = AwtColor(95, 100, 125)
  private val Text       = AwtColor(240, 240, 250)
  private val Muted      = AwtColor(150, 150, 170)
  private val Gold       = AwtColor(255, 220, 120)
