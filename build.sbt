name := """decodingus"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.5"

val slickVersion = "6.2.0"
libraryDependencies ++= Seq(
  guice,
  "org.playframework" %% "play-slick" % slickVersion,
  "org.playframework" %% "play-slick-evolutions" % slickVersion,
  "org.postgresql" % "postgresql" % "42.7.5",
  "org.webjars" %% "webjars-play" % "3.0.2",
  "org.webjars" % "bootstrap" % "5.3.5",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
  "org.codehaus.janino" % "janino" % "3.1.12"
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.example.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.example.binders._"
