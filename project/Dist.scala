import sbt._
import sbt.Keys._
import scala.sys.process._

/** `sbt dist`: packages the game for the OS sbt runs on, as a self-contained
  * folder with a native launcher and a trimmed Java runtime, so players need no
  * Java install:
  *
  *   Linux:   target/dist/SquareDefense-linux-x64.tar.gz -> SquareDefense/bin/SquareDefense
  *   Windows: target/dist/SquareDefense-windows-x64.zip  -> SquareDefense\SquareDefense.exe
  *
  * Uses the JDK's jpackage (JDK 17+), which only builds for the OS it runs on, so
  * run it once on each target. Kept in the build rather than a shell script so it
  * works the same from bash and from a plain Windows cmd.
  */
object Dist {
  val AppName = "SquareDefense"
  val MainClass = "td.Main"

  /** The JDK modules the game and its libraries need (found with jdeps). */
  val Modules = "java.base,java.desktop,jdk.unsupported"

  val dist = taskKey[File]("Package the game for this OS (native launcher + bundled runtime)")

  private def windows: Boolean = sys.props("os.name").toLowerCase.startsWith("windows")

  /** This OS's x64 LWJGL natives plus libGDX's all-in-one natives; other platforms' are dropped. */
  private def keep(jar: File, os: String): Boolean = {
    val n = jar.getName
    !n.contains("-natives-") || n.endsWith("-natives-desktop.jar") || n.endsWith(s"-natives-$os.jar")
  }

  /** jpackage next to the java running sbt, else from the PATH. */
  private def jpackage: String = {
    val exe = new File(sys.props("java.home"), "bin/" + (if (windows) "jpackage.exe" else "jpackage"))
    if (exe.isFile) exe.getAbsolutePath else "jpackage"
  }

  def settings: Seq[Setting[_]] = Seq(
    dist := {
      val log     = streams.value.log
      val os      = if (windows) "windows" else "linux"
      val root    = (ThisBuild / baseDirectory).value
      val input   = root / "target" / "package" / "input"
      val out     = root / "target" / "dist"
      val mainJar = (Compile / packageBin).value
      val jars    = (Runtime / fullClasspathAsJars).value.map(_.data).filter(f => keep(f, os))

      IO.delete(Seq(input, out / AppName))
      IO.createDirectory(input)
      IO.createDirectory(out)
      jars.foreach(j => IO.copyFile(j, input / j.getName))
      log.info(s"packaging $AppName ${version.value} for $os (${jars.size} jars)")

      val cmd = Seq(
        jpackage,
        "--type", "app-image",
        "--name", AppName,
        "--app-version", version.value,
        "--vendor", "Square Defense",
        "--input", input.getAbsolutePath,
        "--main-jar", mainJar.getName,
        "--main-class", MainClass,
        "--add-modules", Modules,
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=zip-6",
        // keep save data in the user's data folder, not next to the program
        "--java-options", "-Dtd.packaged=true",
        "--dest", out.getAbsolutePath
      )
      if (Process(cmd).!(log) != 0) sys.error("jpackage failed")

      val archive =
        if (windows) {
          val zip = out / s"$AppName-windows-x64.zip"
          IO.delete(zip)
          IO.zip(Path.allSubpaths(out / AppName).map { case (f, rel) => f -> s"$AppName/$rel" }, zip, None)
          zip
        } else {
          // tar keeps the launcher's executable bit, which a zip would lose
          val tgz = out / s"$AppName-linux-x64.tar.gz"
          IO.delete(tgz)
          if (Process(Seq("tar", "-czf", tgz.getAbsolutePath, "-C", out.getAbsolutePath, AppName)).!(log) != 0)
            sys.error("tar failed")
          tgz
        }
      log.info(s"done: $archive (${archive.length / (1024 * 1024)} MB); unpacked app in ${out / AppName}")
      archive
    }
  )
}
