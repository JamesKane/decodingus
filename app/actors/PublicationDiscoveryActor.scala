package actors

import jakarta.inject.Inject
import org.apache.pekko.actor.Actor
import play.api.Logging
import services.PublicationDiscoveryService

import scala.concurrent.ExecutionContext

object PublicationDiscoveryActor {
  case object RunDiscovery
}

class PublicationDiscoveryActor @Inject()(
                                           discoveryService: PublicationDiscoveryService
                                         )(implicit ec: ExecutionContext) extends Actor with Logging {

  import PublicationDiscoveryActor._

  override def receive: Receive = {
    case RunDiscovery =>
      logger.info("Received RunDiscovery message")
      discoveryService.runDiscovery().map { _ =>
        logger.info("Discovery run finished successfully")
      }.recover {
        case e: Exception =>
          logger.error(s"Discovery run failed: ${e.getMessage}", e)
      }
  }
}
