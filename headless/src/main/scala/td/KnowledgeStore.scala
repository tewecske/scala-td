package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.util.Try

/** Saves the explorer's [[Knowledge]] as JSON so training resumes across runs.
  * Lives under `data/`, which is gitignored like the other save files.
  */
object KnowledgeStore:

  /** One file per level: a route's cells mean nothing on another route. `level` is
    * 0-based; the file is numbered from 1 like the level list the player sees.
    */
  def pathFor(biome: String, level: Int): String = s"data/knowledge/$biome/level-${level + 1}.json"

  /** Fingerprint of a level's board: grid size, route and puddles. */
  def routeKey(biome: String, level: Int): String =
    Config.withContext(biome, level) {
      val l = Config.levelAt(level)
      val text = s"${Config.GridSize}|${l.route}|${l.puddles}"
      "%08x".format(scala.util.hashing.MurmurHash3.stringHash(text))
    }

  /** The saved knowledge for a level, or empty knowledge if there is none or it
    * was learned on a different board.
    */
  def forLevel(biome: String, level: Int, log: String => Unit = println): Knowledge =
    val key  = routeKey(biome, level)
    val path = pathFor(biome, level)
    val k    = load(path)
    if k.batches > 0 && k.route != key then
      log(s"  $path was learned on another map; starting from scratch")
      Knowledge(route = key)
    else k.copy(route = key)

  def load(path: String): Knowledge =
    val p = Paths.get(path)
    if !Files.isRegularFile(p) then Knowledge()
    else
      parse(Files.readString(p, StandardCharsets.UTF_8))
        .fold(err => throw IllegalStateException(s"bad knowledge file $path: $err"), identity)

  def save(path: String, k: Knowledge): Unit =
    val p = Paths.get(path)
    Option(p.getParent).foreach(Files.createDirectories(_))
    Files.writeString(p, toJson(k) + "\n", StandardCharsets.UTF_8)

  def toJson(k: Knowledge): String =
    val root = obj(
      "batches"  -> JsonValue(k.batches.toLong),
      "route"    -> JsonValue(k.route),
      "waitRate" -> JsonValue(k.waitRate),
      "towers"   -> obj(k.towerWeights.toSeq.sortBy(_._1).map((n, w) => n -> JsonValue(w))*),
      "cells"    -> arr(k.cellWeights.toSeq.sortBy { case (c, _) => (c.y, c.x) }.map { case (c, w) =>
        arr(Seq(JsonValue(c.x.toLong), JsonValue(c.y.toLong), JsonValue(w)))
      }),
      "rejected" -> arr(k.rejected.toSeq.sortBy(c => (c.y, c.x)).map(c =>
        arr(Seq(JsonValue(c.x.toLong), JsonValue(c.y.toLong))))
      )
    )
    root.prettyPrint(JsonWriter.OutputType.json, 2)

  def parse(text: String): Either[String, Knowledge] =
    Try {
      val root    = JsonReader().parse(text)
      val towers  = root.get("towers")
      val cells   = root.get("cells")
      val rejects = root.get("rejected")
      Knowledge(
        towerWeights = (0 until towers.size).map(i => towers.get(i).name -> towers.get(i).asDouble()).toMap,
        cellWeights = (0 until cells.size).map { i =>
          val c = cells.get(i)
          GridPos(c.get(0).asInt(), c.get(1).asInt()) -> c.get(2).asDouble()
        }.toMap,
        rejected = (0 until rejects.size).map { i =>
          val c = rejects.get(i)
          GridPos(c.get(0).asInt(), c.get(1).asInt())
        }.toSet,
        waitRate = root.getDouble("waitRate"),
        batches = root.getInt("batches"),
        route = root.getString("route", "")
      )
    }.toEither.left.map(_.getMessage)

  private def obj(fields: (String, JsonValue)*): JsonValue =
    val o = JsonValue(JsonValue.ValueType.`object`)
    fields.foreach((k, v) => o.addChild(k, v))
    o

  private def arr(items: Seq[JsonValue]): JsonValue =
    val a = JsonValue(JsonValue.ValueType.array)
    items.foreach(a.addChild(_))
    a
