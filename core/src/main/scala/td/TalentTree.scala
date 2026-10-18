package td

/** The talent tree as a picture: which column and row each talent goes in, and
  * the lines from each talent to the ones it unlocks. The game's talent screen
  * and the editor windows draw the same layout.
  *
  * Columns are the layers (filler, core, deep). Within a column, a talent sits
  * next to the talents it needs (ordered by the mean row of its prerequisites,
  * then config order), so lines stay short and rarely cross.
  */
object TalentTree:

  final case class Node(talent: Talent, column: Int, row: Int)

  /** prerequisite id -> the id of a talent that needs it */
  final case class Edge(from: String, to: String)

  def column(layer: TalentLayer): Int = layer match
    case TalentLayer.Filler => 0
    case TalentLayer.Core   => 1
    case TalentLayer.Deep   => 2

  val Columns: Int = TalentLayer.values.length

  def layout(talents: Vector[Talent]): Vector[Node] =
    val configIndex = talents.map(_.id).zipWithIndex.toMap
    (0 until Columns).foldLeft(Vector.empty[Node]) { (placed, col) =>
      val rowOf = placed.map(n => n.talent.id -> n.row).toMap
      def anchor(t: Talent): Double =
        val rows = t.prereqs.flatMap(rowOf.get)
        if rows.isEmpty then Double.MaxValue else rows.sum.toDouble / rows.size
      val here = talents.filter(t => column(t.layer) == col)
        .sortBy(t => (if col == 0 then 0.0 else anchor(t), configIndex(t.id)))
      // each talent moves down to the level of what it needs, leaving gaps above it
      val rows = here.foldLeft(Vector.empty[Int]) { (acc, t) =>
        val next = acc.lastOption.fold(0)(_ + 1)
        val a    = anchor(t)
        acc :+ (if col == 0 || a == Double.MaxValue then next else math.max(next, math.round(a).toInt))
      }
      placed ++ here.zip(rows).map((t, row) => Node(t, col, row))
    }

  def edges(talents: Vector[Talent]): Vector[Edge] =
    val ids = talents.map(_.id).toSet
    talents.flatMap(t => t.prereqs.filter(ids).map(p => Edge(p, t.id)))

  /** Rows the tree needs (the lowest row used, plus one). */
  def rows(talents: Vector[Talent]): Int =
    layout(talents).map(_.row + 1).maxOption.getOrElse(0)

  /** A talent can be bought once every talent it needs is owned. */
  def available(t: Talent, owned: Set[String]): Boolean = t.prereqs.forall(owned.contains)
