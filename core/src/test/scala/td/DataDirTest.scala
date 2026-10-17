package td

class DataDirTest extends munit.FunSuite:

  private def withProps[A](props: (String, Option[String])*)(f: => A): A =
    val before = props.map((k, _) => k -> sys.props.get(k))
    props.foreach { case (k, v) => v.fold(System.clearProperty(k))(System.setProperty(k, _)) }
    try f
    finally before.foreach { case (k, v) => v.fold(System.clearProperty(k))(System.setProperty(k, _)) }

  test("save data stays in ./data unless a packaged build moves it to the user's folder") {
    withProps("td.data" -> None, "td.packaged" -> None) {
      DataDir.forPackagedBuild()
      assertEquals(DataDir.root, "data")
      assertEquals(Meta.defaultPath, java.nio.file.Paths.get("data", "meta.json").toString)
    }
    withProps("td.data" -> None, "td.packaged" -> Some("true")) {
      DataDir.forPackagedBuild()
      assertEquals(DataDir.root, DataDir.userDir)
      assert(Journal.defaultPath.startsWith(DataDir.userDir))
    }
    withProps("td.data" -> Some("/elsewhere"), "td.packaged" -> Some("true")) {
      DataDir.forPackagedBuild()
      assertEquals(DataDir.root, "/elsewhere")
    }
  }
