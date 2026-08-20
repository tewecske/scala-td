val gdxVersion = "1.14.2"

lazy val commonSettings = Seq(
  version      := "0.1.0",
  scalaVersion := "3.3.8",
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")
)

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
  .settings(
    name := "game",
    // LWJGL3 needs its own JVM; running inside sbt's process is unreliable.
    fork := true,
    libraryDependencies ++= Seq(
      "com.badlogicgames.gdx" % "gdx-backend-lwjgl3" % gdxVersion,
      "com.badlogicgames.gdx" % "gdx-platform"       % gdxVersion classifier "natives-desktop"
    )
  )

lazy val editor = (project in file("editor"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "editor")

lazy val headless = (project in file("headless"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(name := "headless")

lazy val root = (project in file("."))
  .aggregate(core, game, editor, headless)
  .settings(commonSettings)
  .settings(name := "towerdefense")
