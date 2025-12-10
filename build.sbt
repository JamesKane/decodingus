name := """decodingus"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "3.3.6"

val SLICK_VERSION = "6.2.0"
val SLICK_PG_VERSION = "0.23.1"
val TAPIR_VERSION = "1.11.50"
val AWS_VERSION = "2.40.3"

// WARNING: Updating beyond 1.1.2 will result in startup errors, since quartz schedular needs this version
val APACHE_PEKKO_VERSION = "1.1.5"

scalacOptions ++= Seq("-Xmax-inlines", "128")

libraryDependencies ++= Seq(
  guice,
  caffeine,
  "org.scala-lang.modules" %% "scala-xml" % "2.4.0",
  "org.playframework" %% "play-slick" % SLICK_VERSION,
  "org.playframework" %% "play-slick-evolutions" % SLICK_VERSION,
  "org.postgresql" % "postgresql" % "42.7.8",
  "com.github.tminglei" %% "slick-pg" % SLICK_PG_VERSION,
  "com.github.tminglei" %% "slick-pg_jts" % SLICK_PG_VERSION,
  "com.github.tminglei" %% "slick-pg_play-json" % SLICK_PG_VERSION,
  "org.webjars" %% "webjars-play" % "3.0.9",
  "org.webjars" % "bootstrap" % "5.3.8",
  "org.webjars" % "popper.js" % "2.11.7",
  "org.webjars.npm" % "htmx.org" % "2.0.8",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test,
  "com.h2database" % "h2" % "2.4.240" % Test,
  "org.codehaus.janino" % "janino" % "3.1.12",
  "com.nappin" %% "play-recaptcha" % "3.0",

  // Core Tapir libraries
  "com.softwaremill.sttp.tapir" %% "tapir-core" % TAPIR_VERSION,
  "com.softwaremill.sttp.tapir" %% "tapir-json-play" % TAPIR_VERSION,

  // Play server interpreter
  "com.softwaremill.sttp.tapir" %% "tapir-play-server" % TAPIR_VERSION,

  // OpenAPI / Swagger UI generation
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % TAPIR_VERSION,

  "io.github.samueleresca" %% "pekko-quartz-scheduler" % "1.3.0-pekko-1.1.x",

  "org.apache.pekko" %% "pekko-protobuf-v3" % APACHE_PEKKO_VERSION,
  "org.apache.pekko" %% "pekko-serialization-jackson" % APACHE_PEKKO_VERSION,
  "org.apache.pekko" %% "pekko-stream" % APACHE_PEKKO_VERSION,
  "org.apache.pekko" %% "pekko-actor-typed" % APACHE_PEKKO_VERSION,
  "org.apache.pekko" %% "pekko-slf4j" % APACHE_PEKKO_VERSION,

  "software.amazon.awssdk" % "secretsmanager" % AWS_VERSION,
  "software.amazon.awssdk" % "ses" % AWS_VERSION,
  "org.hashids" % "hashids" % "1.0.3",
  "org.mindrot" % "jbcrypt" % "0.4", // BCrypt for password hashing
  "com.github.samtools" % "htsjdk" % "4.3.0",
  "org.scalatestplus" %% "mockito-5-10" % "3.2.18.0" % Test
)

// Code Coverage Configuration
coverageMinimumStmtTotal := 5
coverageFailOnMinimum := true
coverageHighlighting := true
