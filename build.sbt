name := """decodingus"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.5"

val SLICK_VERSION = "6.2.0"
val SLICK_PG_VERSION = "0.23.0"
libraryDependencies ++= Seq(
  guice,
  "org.playframework" %% "play-slick" % SLICK_VERSION,
  "org.playframework" %% "play-slick-evolutions" % SLICK_VERSION,
  "org.postgresql" % "postgresql" % "42.7.5",
  "com.github.tminglei" %% "slick-pg" % SLICK_PG_VERSION,
  "com.github.tminglei" %% "slick-pg_jts" % SLICK_PG_VERSION,
  "com.github.tminglei" %% "slick-pg_play-json" % SLICK_PG_VERSION,
  "org.webjars" %% "webjars-play" % "3.0.2",
  "org.webjars" % "bootstrap" % "5.3.5",
  "org.webjars" % "popper.js" % "2.11.7",
  "org.webjars.npm" % "htmx.org" % "2.0.4",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
  "org.codehaus.janino" % "janino" % "3.1.12"
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.example.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.example.binders._"
