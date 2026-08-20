package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.io.{File, PrintWriter}
import scala.io.Source

/** Permanent meta-progression: currency balance + owned talent ids. */
case class MetaData(balance: Int, talents: Vector[String])

object MetaData:
  val empty: MetaData = MetaData(0, Vector.empty)

/** Stat/flag effects of a set of owned talents, resolved against config. */
case class TalentEffects(
    owned: Set[String],
    damageMult: Float,
    rangeMult: Float,
    goldMult: Float,
    loadoutSlots: Int,
    rerollUnlocked: Boolean
):
  def owns(id: String): Boolean = owned.contains(id)
  def activeCores: Set[String] = layerIds(TalentLayer.Core)
  def activeDeeps: Set[String] = layerIds(TalentLayer.Deep)
  private def layerIds(layer: TalentLayer): Set[String] =
    Config.talents.filter(_.layer == layer).map(_.id).toSet & owned

object TalentEffects:
  val none: TalentEffects = TalentEffects(Set.empty, 1f, 1f, 1f, 0, false)

object Meta:
  val FileName    = "meta.json"
  val defaultPath = s"data/$FileName"

  // -- persistence (mirrors Journal) -----------------------------------------
  def load(): Either[String, MetaData] = load(defaultPath)
  def load(path: String): Either[String, MetaData] =
    val f = File(path)
    if !f.isFile then Right(MetaData.empty)
    else
      try Right(parse(JsonReader().parse(Source.fromFile(f).mkString)))
      catch case e: Exception => Left(s"meta parse error: ${e.getMessage}")

  def save(data: MetaData): Unit = save(defaultPath, data)
  def save(path: String, data: MetaData): Unit =
    val root = JsonValue(JsonValue.ValueType.`object`)
    root.addChild("balance", JsonValue(data.balance))
    root.addChild("talents", strArray(data.talents))
    val f = File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new PrintWriter(f)
    try w.write(root.prettyPrint(JsonWriter.OutputType.json, 2))
    finally w.close()

  def active(): MetaData = load().getOrElse(MetaData.empty)

  private def parse(root: JsonValue): MetaData =
    MetaData(root.getInt("balance", 0), strArray(root, "talents"))

  private def strArray(root: JsonValue, name: String): Vector[String] =
    val child = root.get(name)
    if child == null || !child.isArray then Vector.empty
    else (0 until child.size).map(i => child.get(i).asString()).toVector

  private def strArray(values: Vector[String]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    values.foreach(s => a.addChild(JsonValue(s)))
    a

  // -- payout -----------------------------------------------------------------
  /** Currency earned at run end, scaled by progress; win adds a flat bonus.
    * Every number comes from the `meta` config section.
    */
  def payout(w: World): Int =
    val levels = if w.won then Config.levelCount else w.level
    val base   = levels * Config.PayoutPerLevel + w.wave * Config.PayoutPerWave
    val bonus  = if w.won then Config.PayoutWinBonus else 0
    math.max(0, base + bonus)

  def bank(w: World, data: MetaData): MetaData =
    data.copy(balance = data.balance + payout(w))

  def bankRun(w: World): MetaData =
    val data = load().getOrElse(MetaData.empty)
    val next = bank(w, data)
    save(defaultPath, next)
    next

  // -- talent tree ------------------------------------------------------------
  /** Aggregate active effects from owned talent ids (pure, no IO). */
  def effects(talents: Vector[String]): TalentEffects =
    talents.foldLeft(TalentEffects.none) { (acc, id) =>
      Config.talents.find(_.id == id).fold(acc)(t => add(acc, t))
    }

  private def add(acc: TalentEffects, t: Talent): TalentEffects =
    val owned = acc.owned + t.id
    val p     = t.effect.params
    t.effect.kind match
      case "damageBonus"  => acc.copy(owned = owned, damageMult = acc.damageMult + p.getOrElse("mult", 0f))
      case "rangeBonus"   => acc.copy(owned = owned, rangeMult  = acc.rangeMult  + p.getOrElse("mult", 0f))
      case "goldBonus"    => acc.copy(owned = owned, goldMult   = acc.goldMult   + p.getOrElse("mult", 0f))
      case "loadoutSlots" => acc.copy(owned = owned, loadoutSlots = acc.loadoutSlots + p.getOrElse("slots", 0f).toInt)
      case "reroll"       => acc.copy(owned = owned, rerollUnlocked = true)
      case _              => acc.copy(owned = owned)

  /** Pure purchase: validates id, duplicates, prereqs (layer gating), cost. */
  def purchase(data: MetaData, id: String): Either[String, MetaData] =
    Config.talents.find(_.id == id) match
      case None => Left(s"unknown talent '$id'")
      case Some(t) =>
        if data.talents.contains(id) then Left(s"talent '$id' already purchased")
        else
          val missing = t.prereqs.filterNot(data.talents.contains)
          if missing.nonEmpty then Left(s"talent '$id' requires: ${missing.mkString(", ")}")
          else if data.balance < t.cost then Left(s"talent '$id' costs ${t.cost}, balance is ${data.balance}")
          else Right(data.copy(balance = data.balance - t.cost, talents = data.talents :+ id))

  /** Headless/scriptable purchase: load -> purchase -> save. */
  def purchase(id: String): Either[String, MetaData] =
    val data = load().getOrElse(MetaData.empty)
    purchase(data, id).map { d =>
      save(defaultPath, d)
      d
    }
