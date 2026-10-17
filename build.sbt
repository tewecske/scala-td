val gdxVersion = "1.14.2"

lazy val commonSettings = Seq(
  version      := "0.1.0",
  scalaVersion := "3.3.8",
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")
)

// The simulation reads tuning from the global `Config` singleton (and `Grid`
// caches derived routes per the active biome). Those are process-global, so
// parallel test suites would trample each other's active-biome state. Run the
// tests sequentially to keep that shared state consistent. Scoped to ThisBuild
// so every subproject (the tests live in `core`) inherits it.
ThisBuild / Test / parallelExecution := false

lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.badlogicgames.gdx" % "gdx" % gdxVersion,
      "org.scalameta"        %% "munit" % "1.0.4" % Test
    )
  )

lazy val game = (project in file("game"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(Dist.settings)
  .settings(
    name := "game",
    // LWJGL3 needs its own JVM; running inside sbt's process is unreliable.
    fork := true,
    // Forked runs start in the repo root so save data (data/) and the config
    // override are shared by the GUI and the headless tools.
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    libraryDependencies ++= Seq(
      "com.badlogicgames.gdx" % "gdx-backend-lwjgl3" % gdxVersion,
      "com.badlogicgames.gdx" % "gdx-platform"       % gdxVersion classifier "natives-desktop",
      // Crisp text: TrueType fonts rasterized at the window's real pixel size.
      "com.badlogicgames.gdx" % "gdx-freetype"          % gdxVersion,
      "com.badlogicgames.gdx" % "gdx-freetype-platform" % gdxVersion classifier "natives-desktop"
    )
  )

lazy val editor = (project in file("editor"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "editor",
    Compile / mainClass := Some("td.ConfigEditorApp")
  )

lazy val headless = (project in file("headless"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "headless",
    // Separate JVM so sys.exit(code) kills only the run, not sbt.
    fork := true,
    Compile / mainClass := Some("td.HeadlessMain"),
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    libraryDependencies += "org.scalameta" %% "munit" % "1.0.4" % Test
  )

lazy val root = (project in file("."))
  .aggregate(core, game, editor, headless)
  .settings(commonSettings)
  // `sbt dist` from the root packages the game
  .settings(Dist.dist := (game / Dist.dist).value)
  .settings(name := "towerdefense")
