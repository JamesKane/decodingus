package controllers

import actions.PdsAuthAction
import jakarta.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.*
import services.ibd.{MatchDiscoveryService, PopulationAnalysisService}

import java.util.UUID
import scala.concurrent.ExecutionContext

@Singleton
class MatchDiscoveryController @Inject()(
  val controllerComponents: ControllerComponents,
  pdsAuth: PdsAuthAction,
  discoveryService: MatchDiscoveryService,
  populationService: PopulationAnalysisService
)(implicit ec: ExecutionContext) extends BaseController with Logging {

  def getSuggestions(suggestionType: Option[String], limit: Int): Action[AnyContent] = pdsAuth.async { request =>
    val did = request.pdsNode.did
    // The PDS node's DID is used to look up the user's sample; for now we extract from query context
    // In practice, the Edge client passes the sampleGuid it wants suggestions for
    request.getQueryString("sampleGuid") match {
      case Some(guidStr) =>
        val sampleGuid = UUID.fromString(guidStr)
        discoveryService.getSuggestions(sampleGuid, suggestionType, limit).map { suggestions =>
          Ok(Json.toJson(suggestions.map { s =>
            Json.obj(
              "id" -> s.id,
              "targetSampleGuid" -> s.targetSampleGuid,
              "suggestedSampleGuid" -> s.suggestedSampleGuid,
              "suggestionType" -> s.suggestionType,
              "score" -> s.score,
              "metadata" -> s.metadata,
              "status" -> s.status,
              "createdAt" -> s.createdAt,
              "expiresAt" -> s.expiresAt
            )
          }))
        }
      case None =>
        scala.concurrent.Future.successful(BadRequest(Json.obj("error" -> "sampleGuid query parameter required")))
    }
  }

  def dismissSuggestion(id: Long): Action[AnyContent] = pdsAuth.async { _ =>
    discoveryService.dismissSuggestion(id).map { success =>
      Ok(Json.obj("success" -> success))
    }
  }

  def getPopulationBreakdown(sampleGuid: UUID): Action[AnyContent] = pdsAuth.async { _ =>
    populationService.getBreakdown(sampleGuid).map {
      case Some(cache) => Ok(Json.obj(
        "sampleGuid" -> cache.sampleGuid,
        "breakdown" -> cache.breakdown,
        "cachedAt" -> cache.cachedAt
      ))
      case None => NotFound(Json.obj("error" -> s"No population breakdown for sample $sampleGuid"))
    }
  }

  def getPopulationOverlap(guid1: UUID, guid2: UUID): Action[AnyContent] = pdsAuth.async { _ =>
    populationService.computeOverlap(guid1, guid2).map {
      case Some(score) => Ok(Json.obj("overlapScore" -> score))
      case None => NotFound(Json.obj("error" -> "Population data not available for one or both samples"))
    }
  }
}
