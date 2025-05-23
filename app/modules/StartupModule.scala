package modules

import jakarta.inject.Inject
import play.api.Logging
import play.api.inject.{ApplicationLifecycle, SimpleModule, bind}
import services.TreeInitializationService

import scala.concurrent.{ExecutionContext, Future}

class StartupModule extends SimpleModule(bind[StartupService].toSelf.eagerly())

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
