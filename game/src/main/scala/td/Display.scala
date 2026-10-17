package td

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics.DisplayMode
import com.badlogic.gdx.backends.lwjgl3.{Lwjgl3ApplicationConfiguration, Lwjgl3Graphics}

/** Applies [[DisplaySettings]] to the window: at startup through the launcher
  * configuration, and live from the settings screen.
  */
object Display:

  /** The resolution choices for `mode` on the current monitor. */
  def resolutions(mode: WindowMode): Vector[(Int, Int)] =
    val native = Gdx.graphics.getDisplayMode
    mode match
      case WindowMode.Windowed   => DisplaySettings.windowSizes(native.width, native.height)
      case WindowMode.Fullscreen => sizes(Gdx.graphics.getDisplayModes.toVector)
      case WindowMode.Borderless => Vector((native.width, native.height))

  private def sizes(modes: Vector[DisplayMode]): Vector[(Int, Int)] =
    modes.map(m => (m.width, m.height)).distinct.sortBy((w, h) => (w, h))

  /** The fullscreen mode for a size: its highest refresh rate, or the closest size. */
  private def modeFor(modes: Vector[DisplayMode], w: Int, h: Int, fallback: DisplayMode): DisplayMode =
    val exact = modes.filter(m => m.width == w && m.height == h)
    if exact.nonEmpty then exact.maxBy(m => (m.refreshRate, m.bitsPerPixel))
    else if modes.nonEmpty then modes.minBy(m => math.abs(m.width * m.height - w * h))
    else fallback

  /** Sets up the launcher so the first frame already has the saved settings. */
  def configure(config: Lwjgl3ApplicationConfiguration, s: DisplaySettings): Unit =
    val native = Lwjgl3ApplicationConfiguration.getDisplayMode
    s.mode match
      case WindowMode.Fullscreen =>
        val modes = Lwjgl3ApplicationConfiguration.getDisplayModes.toVector
        config.setFullscreenMode(modeFor(modes, s.width, s.height, native))
      case WindowMode.Borderless =>
        config.setDecorated(false)
        config.setWindowedMode(native.width, native.height)
        config.setWindowPosition(0, 0)
      case WindowMode.Windowed =>
        config.setWindowedMode(math.min(s.width, native.width), math.min(s.height, native.height))

  /** Switches the running window to `s`. */
  def apply(s: DisplaySettings): Unit =
    val g      = Gdx.graphics
    val native = g.getDisplayMode
    s.mode match
      case WindowMode.Fullscreen =>
        g.setFullscreenMode(modeFor(g.getDisplayModes.toVector, s.width, s.height, native))
      case WindowMode.Borderless =>
        g.setUndecorated(true)
        g.setWindowedMode(native.width, native.height)
        window.foreach(_.setPosition(0, 0))
      case WindowMode.Windowed =>
        val w = math.min(s.width, native.width)
        val h = math.min(s.height, native.height)
        g.setUndecorated(false)
        g.setWindowedMode(w, h)
        window.foreach(_.setPosition((native.width - w) / 2, (native.height - h) / 2))

  private def window =
    Gdx.graphics match
      case l: Lwjgl3Graphics => Option(l.getWindow)
      case _                 => None
