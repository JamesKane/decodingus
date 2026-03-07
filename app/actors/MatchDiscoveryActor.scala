package actors

import jakarta.inject.Inject
import org.apache.pekko.actor.Actor
import play.api.Logging
import services.ibd.{MatchDiscoveryService, PopulationAnalysisService}

import scala.concurrent.ExecutionContext

object MatchDiscoveryActor {
  case object RunDiscovery
}

class MatchDiscoveryActor @Inject()(
  populationAnalysisService: PopulationAnalysisService,
  matchDiscoveryService: MatchDiscoveryService
)(implicit ec: ExecutionContext) extends Actor with Logging {

  import MatchDiscoveryActor.*

  override def receive: Receive = {
    case RunDiscovery =>
      logger.info("Starting match discovery computation")
      for {
        overlapCount <- populationAnalysisService.computeAllOverlapScores()
        suggestionCount <- matchDiscoveryService.generateSuggestions()
      } yield {
        logger.info(s"Match discovery complete: $overlapCount overlap scores computed, $suggestionCount suggestions generated")
      }
  }
}
