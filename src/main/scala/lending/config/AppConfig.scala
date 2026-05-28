package lending.config

import cats.effect.Sync

final case class DbConfig(
    host: String,
    port: Int,
    user: String,
    password: Option[String],
    database: String,
    poolMax: Int
)
final case class HttpConfig(host: String, port: Int)
final case class SecretsConfig(ssnPepperSeed: String)
final case class AppConfig(
    db: DbConfig,
    http: HttpConfig,
    secrets: SecretsConfig
)

object AppConfig {
  def load[F[_]: Sync]: F[AppConfig] = Sync[F].delay {
    def env(name: String): Option[String] = sys.env.get(name).filter(_.nonEmpty)
    def req(name: String): String =
      env(name).getOrElse(sys.error(s"missing env var: $name"))
    def int(name: String, default: Int): Int =
      env(name).map(_.toInt).getOrElse(default)

    AppConfig(
      DbConfig(
        env("DB_HOST").getOrElse("localhost"),
        int("DB_PORT", 5432),
        req("DB_USER"),
        env("DB_PASSWORD"),
        req("DB_NAME"),
        int("DB_POOL_MAX", 8)
      ),
      HttpConfig(env("HTTP_HOST").getOrElse("0.0.0.0"), int("HTTP_PORT", 8080)),
      SecretsConfig(req("SSN_PEPPER_SEED"))
    )
  }
}
