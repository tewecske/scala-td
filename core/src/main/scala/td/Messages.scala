package td

import java.util.Properties
import scala.jdk.CollectionConverters.*

/** Code -> text lookup for user-facing strings. Translations live in
  * `messages/messages_<lang>.properties` on the classpath (`code=text`); the
  * active language comes from config (`Config.game.i18n.lang`). A missing code
  * falls back to the code itself so an untranslated key is visible instead of
  * crashing. When `showCodes` is on, `t` returns the code unchanged — a debug
  * mode for finding keys. It starts from config and is toggled at runtime
  * (Alt+I in the game). Headless-safe: no GL, only JDK `Properties`.
  */
object Messages:
  /** Debug: when true, `t` returns the key instead of the translation. Initial
    * value comes from config; the game toggles it with Alt+I.
    */
  @volatile var showCodes: Boolean = Config.game.i18n.showCodes

  private lazy val props: Map[String, String] = load()

  def t(code: String): String =
    if showCodes then code else props.getOrElse(code, code)

  def t(code: String, args: Any*): String =
    if showCodes then code
    else String.format(props.getOrElse(code, code), args.map(_.asInstanceOf[AnyRef])*)

  private def load(): Map[String, String] =
    val lang = Config.game.i18n.lang
    val in   = getClass.getResourceAsStream(s"/messages/messages_$lang.properties")
    if in == null then Map.empty
    else
      val p = Properties()
      p.load(in)
      p.stringPropertyNames().asScala.map(k => k -> p.getProperty(k)).toMap
