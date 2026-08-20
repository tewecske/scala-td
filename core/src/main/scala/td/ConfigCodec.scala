package td

/** Text codecs for the array-valued config fields, so the Swing editor edits
  * them as plain strings while validation stays in the loader (via re-parse).
  * Syntax is editor-facing; the on-disk JSON shape is untouched (ConfigWriter).
  */
object ConfigCodec:

  def formatComposition(c: Vector[(String, Int)]): String =
    c.map((n, k) => s"$n:$k").mkString(",")

  def parseComposition(s: String): Either[String, Vector[(String, Int)]] =
    if s.trim.isEmpty then Right(Vector.empty)
    else
      s.split(",").toVector.map(_.trim).foldLeft[Either[String, Vector[(String, Int)]]](Right(Vector.empty)) {
        (acc, tok) =>
          for
            xs <- acc
            pair <- tok.split(":", 2) match
              case Array(n, k) =>
                k.trim.toIntOption match
                  case Some(i) if i >= 0 => Right(n.trim -> i)
                  case _                 => Left(s"bad composition entry '$tok' (want name:count)")
              case _ => Left(s"bad composition entry '$tok' (want name:count)")
          yield xs :+ pair
      }

  def formatWaypoints(ws: Vector[GridPos]): String =
    ws.map(p => s"${p.x},${p.y}").mkString(" ")

  def parseWaypoints(s: String): Either[String, Vector[GridPos]] =
    if s.trim.isEmpty then Right(Vector.empty)
    else
      s.trim.split("\\s+").toVector.foldLeft[Either[String, Vector[GridPos]]](Right(Vector.empty)) {
        (acc, tok) =>
          for
            xs <- acc
            p <- tok.split(",", 2) match
              case Array(x, y) => (x.trim.toIntOption, y.trim.toIntOption) match
                case (Some(px), Some(py)) => Right(GridPos(px, py))
                case _                    => Left(s"bad waypoint '$tok' (want x,y)")
              case _ => Left(s"bad waypoint '$tok' (want x,y)")
          yield xs :+ p
      }

  def formatNames(ns: Vector[String]): String = ns.mkString(",")

  def parseNames(s: String): Vector[String] =
    s.split(",").toVector.map(_.trim).filter(_.nonEmpty)

  def formatBranches(bs: Vector[Branch]): String =
    bs.map(b => s"side:${b.side} from:${b.from} to:${b.to} waypoints:${formatWaypoints(b.waypoints)}")
      .mkString("; ")

  def parseBranches(s: String): Either[String, Vector[Branch]] =
    if s.trim.isEmpty then Right(Vector.empty)
    else
      s.split(";").toVector.map(_.trim).foldLeft[Either[String, Vector[Branch]]](Right(Vector.empty)) {
        (acc, tok) =>
          for
            xs <- acc
            b <- parseBranch(tok)
          yield xs :+ b
      }

  private def parseBranch(tok: String): Either[String, Branch] =
    val parts = tok.split("\\s+").toVector.map(_.trim)
    val side  = parts.find(_.startsWith("side:")).map(_.stripPrefix("side:"))
    val from  = parts.find(_.startsWith("from:")).flatMap(_.stripPrefix("from:").toIntOption)
    val to    = parts.find(_.startsWith("to:")).flatMap(_.stripPrefix("to:").toIntOption)
    val wps   = parts.find(_.startsWith("waypoints:")).map(_.stripPrefix("waypoints:")).getOrElse("")
    for
      s <- side.flatMap(n => RouteSide.values.find(_.toString == n)).toRight(s"bad branch '$tok' (side)")
      fr <- from.toRight(s"bad branch '$tok' (from)")
      to <- to.toRight(s"bad branch '$tok' (to)")
      ws <- parseWaypoints(wps)
    yield Branch(s, fr, to, ws)