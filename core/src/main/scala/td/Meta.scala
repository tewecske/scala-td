package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.io.{File, PrintWriter}
import scala.io.Source

/** Permanent meta-progression: currency balance, owned talent ids, and towers
  * bought out of the loadout's locked list.
  */
case class MetaData(balance: Int, talents: Vector[String], towers: Vector[String] = Vector.empty)

object MetaData:
  val empty: MetaData = MetaData(0, Vector.empty)

/** Stat/flag effects of a set of owned talents, resolved against config. */
case class TalentEffects(
    owned: Set[String],
    damageMult: Float,
    rangeMult: Float,
    goldMult: Float,
    loadoutSlots: Int,
    rerollUnlocked: Boolean,
    extraLives: Int = 0,
    startGold: Int = 0,
    fireRateMult: Float = 1f,
    critChance: Float = 0f,
    critDamage: Float = 0f,
    kindDamage: Map[DamageKind, Float] = Map.empty,
    shredPerHit: Float = 0f,
    shredMax: Float = 0f
):
  def owns(id: String): Boolean = owned.contains(id)
  /** Damage multiplier for one damage kind from the per-kind talents (1 = none). */
  def kindMult(kind: DamageKind): Float = 1f + kindDamage.getOrElse(kind, 0f)
  /** A critical hit's damage as a multiple of a normal hit. */
  def critMult: Float = Config.CritMult + critDamage
  def activeCores: Set[String] = layerIds(TalentLayer.Core)
  def activeDeeps: Set[String] = layerIds(TalentLayer.Deep)
  private def layerIds(layer: TalentLayer): Set[String] =
    Config.talents.filter(_.layer == layer).map(_.id).toSet & owned

object TalentEffects:
  val none: TalentEffects = TalentEffects(Set.empty, 1f, 1f, 1f, 0, false)

object Meta:
  val FileName    = "meta.json"
  def defaultPath: String = DataDir.path(FileName)

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
    root.addChild("towers", strArray(data.towers))
    val f = File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new PrintWriter(f)
    try w.write(root.prettyPrint(JsonWriter.OutputType.json, 2))
    finally w.close()

  def active(): MetaData = load().getOrElse(MetaData.empty)

  private def parse(root: JsonValue): MetaData =
    MetaData(root.getInt("balance", 0), strArray(root, "talents"), strArray(root, "towers"))

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
      case "extraLives"   => acc.copy(owned = owned, extraLives = acc.extraLives + p.getOrElse("lives", 0f).toInt)
      case "startGold"    => acc.copy(owned = owned, startGold = acc.startGold + p.getOrElse("gold", 0f).toInt)
      case "fireRateBonus" => acc.copy(owned = owned, fireRateMult = acc.fireRateMult + p.getOrElse("mult", 0f))
      case "critChance"   => acc.copy(owned = owned, critChance = acc.critChance + p.getOrElse("chance", 0f))
      case "critDamage"   => acc.copy(owned = owned, critDamage = acc.critDamage + p.getOrElse("mult", 0f))
      // params are keyed by damage kind ("kinetic", "fire", ...), each a damage bonus
      case "kindDamageBonus" =>
        val more = DamageKind.values.toVector.flatMap(k => p.get(k.toString.toLowerCase).map(k -> _))
        acc.copy(owned = owned, kindDamage = more.foldLeft(acc.kindDamage) { case (m, (k, v)) =>
          m.updated(k, m.getOrElse(k, 0f) + v) })
      case "shred" =>
        acc.copy(owned = owned, shredPerHit = acc.shredPerHit + p.getOrElse("perHit", 0f),
          shredMax = math.max(acc.shredMax, p.getOrElse("max", 0f)))
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

  // -- tower unlocks ----------------------------------------------------------
  /** Towers the loadout picker may offer: the config's starting set plus every
    * locked tower this profile has bought.
    */
  def unlockedTowers(data: MetaData): Set[String] =
    Config.Loadout.unlocked.toSet ++ data.towers.filter(Config.Loadout.locked.contains)

  /** Pure purchase of a locked tower, priced by `loadout.unlockCosts`. */
  def purchaseTower(data: MetaData, name: String): Either[String, MetaData] =
    if !Config.Loadout.locked.contains(name) then Left(s"tower '$name' is not locked")
    else if data.towers.contains(name) then Left(s"tower '$name' already unlocked")
    else
      Config.Loadout.unlockCosts.get(name) match
        case None => Left(s"tower '$name' cannot be bought")
        case Some(cost) =>
          if data.balance < cost then Left(s"tower '$name' costs $cost, balance is ${data.balance}")
          else Right(data.copy(balance = data.balance - cost, towers = data.towers :+ name))
