package modules

import actions.{ApiSecurityAction, DevelopmentSecureApiAction, ProductionSecureApiAction}
import com.google.inject.{AbstractModule, Singleton}
import play.api.{Environment, Logging, Mode}

/**
 * A Guice module for configuring API security action bindings based on the application mode.
 *
 * The `ApiSecurityModule` extends `AbstractModule`, enabling dependency injection for handling
 * secure API actions. Depending on the environment mode, it binds the `ApiSecurityAction` interface
 * to either a production or development implementation:
 *
 * - In `Mode.Prod`, binds `ApiSecurityAction` to `ProductionSecureApiAction`
 * for enforcing strict API key validation.
 * - In other modes, binds `ApiSecurityAction` to `DevelopmentSecureApiAction`
 * where API key validation is disabled for ease of local development.
 *
 * The module uses the application's environment to determine the appropriate binding,
 * enabling seamless toggling of security configurations between production and development.
 */
class ApiSecurityModule extends AbstractModule with Logging {
  override def configure(): Unit = {
    val env = Environment.simple()

    env.mode match {
      case Mode.Prod =>
        logger.info("Binding ProductionSecureApiAction for API security")
        bind(classOf[ApiSecurityAction])
          .to(classOf[ProductionSecureApiAction])
          .in(classOf[Singleton])

      case _ =>
        logger.info("Binding DevelopmentSecureApiAction for API security (API key validation disabled)")
        bind(classOf[ApiSecurityAction])
          .to(classOf[DevelopmentSecureApiAction])
          .in(classOf[Singleton])
    }
  }
}
