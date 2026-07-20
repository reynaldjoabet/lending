import sbt.*

object Dependencies {

  private object V {
    val iron = "3.3.2"
    val skunk = "1.1.0-RC1"
    val http4s = "0.23.36"
    val circe = "0.14.16"
    val ce = "3.7.0"
    val log4cats = "2.8.0"
    val logback = "1.5.38"
    val munit = "1.3.4"
    val munitCE = "2.2.0"
    val jsoniter = "2.39.1"
    val fs2 = "3.13.0"
    val fs2Kafka = "4.0.0"
    val chimney = "1.11.0"
    val hedgehog = "0.13.1"
    val scalacheck = "1.19.0"
    val hikaricp = "7.1.0"
    val flyway = "12.11.0"
    val postgres = "42.7.13"
    val bouncycastle = "1.85"
    val password4j = "1.8.4"
    val auth0 = "4.6.0"
    val nimbusJoseJwt = "10.9.1"
    val nimbusOauth2Oidc = "11.38.1"
    val vault = "5.1.0"
    val jwtScala = "11.0.4"
    // --- Cache ---
    val caffeine = "3.2.4"

    // --- Observability ---
    val datadog = "2.56.0"
    val kamon = "2.8.1"
    val otel4s = "0.16.0" // pinned: skunk-core 1.1.0-RC1 requires 0.16.0 (1.0.0 is binary-incompatible)

    // --- Config ---
    val pureconfig = "0.17.10"

    // --- HTTP clients ---
    val sttp = "4.0.26"
  }

  val all: Seq[ModuleID] = Seq(
    "io.github.iltotore" %% "iron" % V.iron,
    "io.github.iltotore" %% "iron-skunk" % V.iron,
    "io.github.iltotore" %% "iron-circe" % V.iron,
    "org.tpolecat" %% "skunk-core" % V.skunk,
    "org.http4s" %% "http4s-ember-server" % V.http4s,
    "org.http4s" %% "http4s-ember-client" % V.http4s,
    "org.http4s" %% "http4s-circe" % V.http4s,
    "org.http4s" %% "http4s-dsl" % V.http4s,
    "io.circe" %% "circe-core" % V.circe,
    "io.circe" %% "circe-generic" % V.circe,
    "io.circe" %% "circe-parser" % V.circe,
    "org.typelevel" %% "cats-effect" % V.ce,
    "org.typelevel" %% "log4cats-slf4j" % V.log4cats,
    "ch.qos.logback" % "logback-classic" % V.logback,
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % V.jsoniter,
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % V.jsoniter % Provided,
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-circe" % V.jsoniter,
    // Streaming
    "co.fs2" %% "fs2-core" % V.fs2,
    "org.typelevel" %% "fs2-kafka" % V.fs2Kafka,
    // Data
    "io.scalaland" %% "chimney" % V.chimney,
    // DB
    "org.postgresql" % "postgresql" % V.postgres,
    "com.zaxxer" % "HikariCP" % V.hikaricp,
    "org.flywaydb" % "flyway-core" % V.flyway,
    // Security
    "com.auth0" % "java-jwt" % V.auth0,
    "com.github.jwt-scala" %% "jwt-core" % V.jwtScala,
    "com.password4j" % "password4j" % V.password4j,
    "com.nimbusds" % "nimbus-jose-jwt" % V.nimbusJoseJwt,
    "com.nimbusds" % "oauth2-oidc-sdk" % V.nimbusOauth2Oidc,
    "org.bouncycastle" % "bcpkix-jdk18on" % V.bouncycastle,
    "org.bouncycastle" % "bcprov-jdk18on" % V.bouncycastle,
    "com.bettercloud" % "vault-java-driver" % V.vault,
    // Cache
    "com.github.ben-manes.caffeine" % "caffeine" % V.caffeine,
    // Observability
    ("com.datadoghq" % "datadog-api-client" % V.datadog)
      .classifier("shaded-jar"),
    "io.kamon" %% "kamon-bundle" % V.kamon,
    "io.kamon" %% "kamon-prometheus" % V.kamon,
    "org.typelevel" %% "otel4s-core" % V.otel4s,
    "org.typelevel" %% "otel4s-oteljava" % V.otel4s,
    // Config
    "com.github.pureconfig" %% "pureconfig-core" % V.pureconfig,
    "com.github.pureconfig" %% "pureconfig-generic-scala3" % V.pureconfig,
    "io.github.iltotore" %% "iron-pureconfig" % V.iron,
    // HTTP clients (exchange / custody)
    "com.softwaremill.sttp.client4" %% "core" % V.sttp,
    "com.softwaremill.sttp.client4" %% "cats" % V.sttp,
    "com.softwaremill.sttp.client4" %% "http4s-backend" % V.sttp,
    "com.softwaremill.sttp.client4" %% "circe" % V.sttp,
    // Testing
    "org.scalameta" %% "munit" % V.munit % Test,
    "org.typelevel" %% "munit-cats-effect" % V.munitCE % Test,
    "qa.hedgehog" %% "hedgehog-core" % V.hedgehog % Test,
    "qa.hedgehog" %% "hedgehog-sbt" % V.hedgehog % Test,
    "org.scalacheck" %% "scalacheck" % V.scalacheck % Test
  )
}
