package td

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.{Pixmap, PixmapIO}
import com.badlogic.gdx.utils.ScreenUtils
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Saves the current frame as a PNG. F12 does it during play (into
  * `data/screenshots`); a screenshot tour (see [[Tour]]) uses it to capture
  * every screen unattended.
  */
object Shots:
  def Dir: String = DataDir.path("screenshots")

  /** Writes the back buffer to `path`. Call after the frame is drawn. */
  def save(path: String): String =
    val w      = Gdx.graphics.getBackBufferWidth
    val h      = Gdx.graphics.getBackBufferHeight
    val pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true)
    // The frame has no meaningful alpha; force it opaque so viewers don't show holes.
    var i = 3
    while i < pixels.length do
      pixels(i) = 255.toByte
      i += 4
    val pm = new Pixmap(w, h, Pixmap.Format.RGBA8888)
    val buf = pm.getPixels
    buf.clear()
    buf.put(pixels)
    buf.position(0)
    val file = if java.nio.file.Paths.get(path).isAbsolute then Gdx.files.absolute(path) else Gdx.files.local(path)
    file.parent().mkdirs()
    PixmapIO.writePNG(file, pm)
    pm.dispose()
    file.file().getAbsolutePath

  /** A timestamped file under [[Dir]]. */
  def timestamped(): String =
    s"$Dir/shot-${LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))}.png"
