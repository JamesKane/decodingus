package startup

import play.api.{Configuration, Environment, Logging, Mode}

import javax.inject.{Inject, Singleton}

/**
 * Validates critical security configuration at application startup.
 * Bound as an eager singleton so it runs immediately on boot.
 */
@Singleton
class SecurityStartupCheck @Inject()(config: Configuration, env: Environment) extends Logging {

  if (env.mode == Mode.Prod) {
    val secret = config.get[String]("play.http.secret.key")
    if (secret == "changeme" || secret.length < 32) {
      throw new IllegalStateException(
        "FATAL: APPLICATION_SECRET is not set or is too short. " +
        "Set the APPLICATION_SECRET environment variable to a secure random value (>= 32 chars)."
      )
    }
    logger.info("Security startup check passed.")
  }
}
