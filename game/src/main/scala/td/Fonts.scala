package td

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter

/** The text styles: a font file and a size in UI units. */
enum TextStyle(val file: String, val size: Float):
  case Title   extends TextStyle("Orbitron-Black.ttf", 112f)
  case Heading extends TextStyle("Orbitron-Bold.ttf", 56f)
  case Card    extends TextStyle("Orbitron-Bold.ttf", 36f)
  case Stat    extends TextStyle("Orbitron-Bold.ttf", 30f)
  case Button  extends TextStyle("Orbitron-Bold.ttf", 25f)
  case Label   extends TextStyle("Inter-SemiBold.ttf", 25f)
  case Body    extends TextStyle("Inter-Regular.ttf", 22f)
  case Small   extends TextStyle("Inter-Regular.ttf", 18f)
  case Popup   extends TextStyle("Inter-SemiBold.ttf", 17f)

/** Every [[TextStyle]] rasterized for the current window: each font is built at
  * its size in screen pixels, so glyphs land 1:1 on the screen and stay sharp at
  * any resolution. [[rebuild]] runs whenever the pixels per UI unit change.
  */
final class Fonts:
  private val Chars = FreeTypeFontGenerator.DEFAULT_CHARS + "·–—…×←→▲▼✓★"

  private var built: Map[TextStyle, BitmapFont] = Map.empty
  private var builtFor: Float = -1f

  def apply(style: TextStyle): BitmapFont = built(style)

  /** Builds the fonts for `pxPerUnit` screen pixels per UI unit, if not already. */
  def rebuild(pxPerUnit: Float): Unit =
    if math.abs(pxPerUnit - builtFor) > 1e-3f then
      dispose()
      val generators = TextStyle.values.map(_.file).distinct
        .map(f => f -> new FreeTypeFontGenerator(Gdx.files.classpath(s"fonts/$f"))).toMap
      built = TextStyle.values.map { style =>
        val p = new FreeTypeFontParameter()
        // small windows: never below 11px, or the small styles stop being readable
        p.size = math.max(11, math.round(style.size * pxPerUnit))
        p.characters = Chars
        p.minFilter = TextureFilter.Linear
        p.magFilter = TextureFilter.Linear
        p.hinting = FreeTypeFontGenerator.Hinting.Slight
        p.kerning = true
        val font = generators(style.file).generateFont(p)
        font.setUseIntegerPositions(true)
        style -> font
      }.toMap
      generators.values.foreach(_.dispose())
      builtFor = pxPerUnit

  def dispose(): Unit =
    built.values.foreach(_.dispose())
    built = Map.empty
    builtFor = -1f
