package modules

import jakarta.inject.Inject
import play.api.Logging
import play.api.inject.{ApplicationLifecycle, SimpleModule, bind}
import services.TreeInitializationService

import scala.concurrent.ExecutionContext

class StartupModule extends SimpleModule(bind[StartupService].toSelf.eagerly())

/**
 * Service responsible for executing startup tasks necessary for the application.
 *
 * The `StartupService` initializes and imports tree data (e.g., haplogroup trees) at application startup by invoking
 * the corresponding methods from the `TreeInitializationService`. Initialization ensures any missing tree data
 * is populated from external files if required. This process is logged to provide visibility into its success or failure.
 *
 * Dependencies required by the service are injected and include:
 *
 * @param treeInitService `TreeInitializationService` responsible for managing tree data initialization.
 * @param lifecycle       `ApplicationLifecycle` for managing application lifecycle hooks (e.g., startup and shutdown).
 * @param ec              Implicit `ExecutionContext` for handling asynchronous operations.
 *
 *                        The initialization process is executed asynchronously. Logging is provided to report the status of the initialization
 *                        for each tree type (e.g., indicating whether data was successfully imported, skipped, or encountered errors).
 *                        If the initialization process fails, the error is logged.
 */
class StartupService @Inject()(
                                treeInitService: TreeInitializationService,
                                lifecycle: ApplicationLifecycle
                              )(implicit ec: ExecutionContext) extends Logging {
  // Run initialization on startup
  treeInitService.initializeIfNeeded().map { results =>
    results.foreach { case (treeType, wasImported) =>
      if (wasImported) {
        logger.info(s"$treeType tree data successfully imported")
      } else {
        logger.info(s"$treeType tree import skipped - either already populated or file missing")
      }
    }
  }.recover {
    case ex => logger.error("Failed during tree initialization", ex)
  }

}
