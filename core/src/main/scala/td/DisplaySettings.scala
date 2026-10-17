package td

import com.badlogic.gdx.utils.{JsonReader, JsonValue, JsonWriter}
import java.io.{File, PrintWriter}
import scala.io.Source

/** How the game window is shown. Borderless is a window without decorations
  * covering the whole monitor at its native resolution.
  */
enum WindowMode:
  case Windowed, Fullscreen, Borderless

/** The player's display choice. `width` x `height` is the window size when
  * windowed and the screen mode when fullscreen; borderless ignores it and uses
  * the monitor's own resolution. A save file, not tuning config.
  */
final case class DisplaySettings(mode: WindowMode = WindowMode.Windowed, width: Int = 1600, height: Int = 900):
  def resolution: String = s"$width x $height"

object DisplaySettings:
  val FileName    = "settings.json"
  val defaultPath = s"data/$FileName"

  /** The saved settings, or the defaults when there are none or they can't be read. */
  def load(path: String = defaultPath): DisplaySettings =
    val f = File(path)
    if !f.isFile then DisplaySettings()
    else
      try
        val root = JsonReader().parse(Source.fromFile(f).mkString)
        val mode = WindowMode.values.find(_.toString == root.getString("mode", "")).getOrElse(WindowMode.Windowed)
        DisplaySettings(mode, root.getInt("width", 1600), root.getInt("height", 900))
      catch case _: Exception => DisplaySettings()

  def save(s: DisplaySettings, path: String = defaultPath): Unit =
    val root = JsonValue(JsonValue.ValueType.`object`)
    root.addChild("mode", JsonValue(s.mode.toString))
    root.addChild("width", JsonValue(s.width.toLong))
    root.addChild("height", JsonValue(s.height.toLong))
    val f = File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new PrintWriter(f)
    try w.write(root.prettyPrint(JsonWriter.OutputType.json, 2))
    finally w.close()

  /** Window sizes offered in windowed mode, up to the monitor's size (which is always offered). */
  val CommonSizes: Vector[(Int, Int)] =
    Vector((1280, 720), (1366, 768), (1600, 900), (1920, 1080), (2560, 1440), (3200, 1800), (3840, 2160))

  def windowSizes(monitorW: Int, monitorH: Int): Vector[(Int, Int)] =
    (CommonSizes.filter((w, h) => w <= monitorW && h <= monitorH) :+ (monitorW, monitorH)).distinct.sortBy(_._1)

  /** The entry after (dir = 1) or before (dir = -1) `current` in `options`, wrapping around;
    * an unknown `current` moves to the option closest in size.
    */
  def cycle(options: Vector[(Int, Int)], current: (Int, Int), dir: Int): (Int, Int) =
    if options.isEmpty then current
    else
      val i = options.indexOf(current) match
        case -1 => options.indices.minBy(k => math.abs(options(k)._1 * options(k)._2 - current._1 * current._2))
        case k  => k + dir
      options(((i % options.size) + options.size) % options.size)
