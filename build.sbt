val gdxVersion = "1.14.2"

lazy val root = (project in file("."))
  .settings(
    name         := "towerdefense",
    version      := "0.1.0",
    scalaVersion := "3.3.8",
    // LWJGL3 needs its own JVM; running inside sbt's process is unreliable.
    fork := true,
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies ++= Seq(
      "com.badlogicgames.gdx" % "gdx"                % gdxVersion,
      "com.badlogicgames.gdx" % "gdx-backend-lwjgl3" % gdxVersion,
      "com.badlogicgames.gdx" % "gdx-platform"       % gdxVersion classifier "natives-desktop"
    )
  )
