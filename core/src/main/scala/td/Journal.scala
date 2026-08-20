package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.io.{File, PrintWriter}
import scala.io.Source

/** A single persisted codex entry: enemy type + trait + precision + optional
  * exact value (only when Studied; Sighting stores the vague hint there).
  */
case class JournalEntry(enemy: String, traitKey: String, tier: Precision, value: Option[String])

/** The persistent journal: discovered traits plus the telegraphed biome/twist
  * knowledge already seen. This is a save file, NOT tuning config.
  */
case class JournalData(entries: Vector[JournalEntry], biomes: Vector[String], twists: Vector[String]):
  def applyEvent(e: DiscoveryEvent): JournalData =
    val idx = entries.indexWhere(en => en.enemy == e.enemy && en.traitKey == e.traitKey)
    val value = if e.value.isEmpty then None else Some(e.value)
    if idx < 0 then copy(entries = entries :+ JournalEntry(e.enemy, e.traitKey, e.tier, value))
    else
      val cur = entries(idx)
      val next =
        if cur.tier == Precision.Sighting && e.tier == Precision.Studied then
          cur.copy(tier = Precision.Studied, value = value)
        else cur
      copy(entries = entries.updated(idx, next))

  def applyRun(run: RunContext): JournalData =
    copy(biomes = (biomes :+ run.biome).distinct, twists = (twists ++ run.twists).distinct)

object JournalData:
  val empty: JournalData = JournalData(Vector.empty, Vector.empty, Vector.empty)

object Journal:
  val FileName    = "journal.json"
  val defaultPath = s"data/$FileName"

  def load(): Either[String, JournalData] = load(defaultPath)

  def load(path: String): Either[String, JournalData] =
    val f = File(path)
    if !f.isFile then Right(JournalData.empty)
    else
      try Right(parse(JsonReader().parse(Source.fromFile(f).mkString)))
      catch case e: Exception => Left(s"journal parse error: ${e.getMessage}")

  def save(data: JournalData): Unit = save(defaultPath, data)

  def save(path: String, data: JournalData): Unit =
    val root    = JsonValue(JsonValue.ValueType.`object`)
    val entries = JsonValue(JsonValue.ValueType.array)
    data.entries.foreach { en =>
      val o = JsonValue(JsonValue.ValueType.`object`)
      o.addChild("enemy", JsonValue(en.enemy))
      o.addChild("trait", JsonValue(en.traitKey))
      o.addChild("tier", JsonValue(en.tier.toString))
      en.value.foreach(v => o.addChild("value", JsonValue(v)))
      entries.addChild(o)
    }
    root.addChild("entries", entries)
    root.addChild("biomes", strArray(data.biomes))
    root.addChild("twists", strArray(data.twists))
    val f = File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new PrintWriter(f)
    try w.write(root.prettyPrint(JsonWriter.OutputType.json, 2))
    finally w.close()

  def record(event: DiscoveryEvent): Unit =
    val data = load(defaultPath).getOrElse(JournalData.empty)
    save(defaultPath, data.applyEvent(event))

  def recordRun(run: RunContext): Unit =
    val data = load(defaultPath).getOrElse(JournalData.empty)
    save(defaultPath, data.applyRun(run))

  private def strArray(values: Vector[String]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    values.foreach(s => a.addChild(JsonValue(s)))
    a

  private def parse(root: JsonValue): JournalData =
    val entries = (0 until root.get("entries").size).map { i =>
      val v = root.get("entries").get(i)
      val valueVal = v.get("value")
      JournalEntry(
        enemy = v.getString("enemy"),
        traitKey = v.getString("trait"),
        tier = Precision.valueOf(v.getString("tier")),
        value = if valueVal == null || valueVal.asString().isEmpty then None else Some(valueVal.asString())
      )
    }.toVector
    JournalData(entries, strArray(root, "biomes"), strArray(root, "twists"))

  private def strArray(root: JsonValue, name: String): Vector[String] =
    val child = root.get(name)
    if child == null || !child.isArray then Vector.empty
    else (0 until child.size).map(i => child.get(i).asString()).toVector
