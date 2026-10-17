package td

import java.nio.file.Paths

/** Where save data lives (journal, meta progress, settings, replays, logs,
  * screenshots). `data` under the working directory by default, which is the
  * repo when developing; the `td.data` system property moves it, and a
  * packaged build points it at the user's data folder (see [[forPackagedBuild]]).
  */
object DataDir:
  def root: String = sys.props.getOrElse("td.data", "data")

  def path(rel: String): String = Paths.get(root, rel).toString

  /** The per-user data folder for an installed copy: %APPDATA%\SquareDefense on
    * Windows, $XDG_DATA_HOME/square-defense (default ~/.local/share) elsewhere.
    */
  def userDir: String =
    val home = sys.props.getOrElse("user.home", ".")
    if sys.props.getOrElse("os.name", "").toLowerCase.startsWith("windows") then
      Paths.get(sys.env.getOrElse("APPDATA", Paths.get(home, "AppData", "Roaming").toString), "SquareDefense").toString
    else
      Paths.get(sys.env.getOrElse("XDG_DATA_HOME", Paths.get(home, ".local", "share").toString), "square-defense").toString

  /** A packaged build (launched with -Dtd.packaged=true) keeps its data in [[userDir]]
    * unless `td.data` says otherwise. Call before anything reads or writes data.
    */
  def forPackagedBuild(): Unit =
    if sys.props.contains("td.packaged") && !sys.props.contains("td.data") then
      System.setProperty("td.data", userDir)
