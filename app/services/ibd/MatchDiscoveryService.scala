package services.ibd

import jakarta.inject.{Inject, Singleton}
import models.domain.ibd.{MatchConsentTracking, MatchRequestTracking, MatchSuggestion}
import play.api.libs.json.{JsValue, Json}
import play.api.{Configuration, Logging}
import repositories.*

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait MatchDiscoveryService {
  // Discovery
  def getSuggestions(sampleGuid: UUID, suggestionType: Option[String], limit: Int): Future[Seq[MatchSuggestion]]
  def dismissSuggestion(id: Long): Future[Boolean]
  def generateSuggestions(): Future[Int]

  // Match requests
  def createMatchRequest(request: MatchRequestTracking): Future[MatchRequestTracking]
  def getMatchRequest(atUri: String): Future[Option[MatchRequestTracking]]
  def getPendingRequests(sampleGuid: UUID): Future[Seq[MatchRequestTracking]]
  def getSentRequests(did: String): Future[Seq[MatchRequestTracking]]
  def cancelRequest(atUri: String): Future[Boolean]

  // Consent
  def trackConsent(consent: MatchConsentTracking): Future[MatchConsentTracking]
  def getConsentStatus(requestUri: String): Future[Option[ConsentStatus]]
  def revokeConsent(atUri: String): Future[Boolean]
}

case class ConsentStatus(
  requestUri: String,
  requesterConsented: Boolean,
  targetConsented: Boolean,
  mutualConsent: Boolean
)

object ConsentStatus {
  implicit val format: play.api.libs.json.OFormat[ConsentStatus] = Json.format[ConsentStatus]
}

@Singleton
class MatchDiscoveryServiceImpl @Inject()(
  suggestionRepo: MatchSuggestionRepository,
  requestRepo: MatchRequestTrackingRepository,
  consentRepo: MatchConsentTrackingRepository,
  overlapScoreRepo: PopulationOverlapScoreRepository,
  breakdownCacheRepo: PopulationBreakdownCacheRepository,
  configuration: Configuration
)(implicit ec: ExecutionContext) extends MatchDiscoveryService with Logging {

  private val populationOverlapThreshold = configuration.getOptional[Double]("decodingus.matching.discovery.population-overlap-threshold").getOrElse(0.6)
  private val maxSuggestionsPerUser = configuration.getOptional[Int]("decodingus.matching.discovery.max-suggestions-per-user").getOrElse(100)
  private val suggestionExpiryDays = configuration.getOptional[Int]("decodingus.matching.discovery.suggestion-expiry-days").getOrElse(90)

  // --- Discovery ---

  override def getSuggestions(sampleGuid: UUID, suggestionType: Option[String], limit: Int): Future[Seq[MatchSuggestion]] =
    suggestionRepo.findByTargetSample(sampleGuid, suggestionType, limit)

  override def dismissSuggestion(id: Long): Future[Boolean] =
    suggestionRepo.dismiss(id)

  override def generateSuggestions(): Future[Int] = {
    for {
      allGuids <- breakdownCacheRepo.findAllSampleGuids()
      count <- generatePopulationOverlapSuggestions(allGuids)
      expired <- suggestionRepo.expireOld(ZonedDateTime.now())
    } yield {
      logger.info(s"Generated $count suggestions, expired $expired old suggestions")
      count
    }
  }

  private def generatePopulationOverlapSuggestions(sampleGuids: Seq[UUID]): Future[Int] = {
    Future.traverse(sampleGuids) { guid =>
      overlapScoreRepo.findBySample(guid, populationOverlapThreshold).flatMap { overlaps =>
        suggestionRepo.countByTargetSample(guid).flatMap { currentCount =>
          val available = maxSuggestionsPerUser - currentCount
          if (available <= 0) Future.successful(0)
          else {
            val newSuggestions = overlaps.take(available).map { overlap =>
              val otherGuid = if (overlap.sampleGuid1 == guid) overlap.sampleGuid2 else overlap.sampleGuid1
              MatchSuggestion(
                id = None,
                targetSampleGuid = guid,
                suggestedSampleGuid = otherGuid,
                suggestionType = "POPULATION_OVERLAP",
                score = overlap.overlapScore,
                metadata = Some(Json.obj("overlapScore" -> overlap.overlapScore)),
                status = "ACTIVE",
                createdAt = ZonedDateTime.now(),
                expiresAt = Some(ZonedDateTime.now().plusDays(suggestionExpiryDays))
              )
            }
            if (newSuggestions.nonEmpty) {
              suggestionRepo.createBatch(newSuggestions).map(_.size).recover {
                case e: Exception =>
                  logger.warn(s"Some suggestions may already exist for $guid: ${e.getMessage}")
                  0
              }
            } else Future.successful(0)
          }
        }
      }
    }.map(_.sum)
  }

  // --- Match Requests ---

  override def createMatchRequest(request: MatchRequestTracking): Future[MatchRequestTracking] =
    requestRepo.create(request)

  override def getMatchRequest(atUri: String): Future[Option[MatchRequestTracking]] =
    requestRepo.findByAtUri(atUri)

  override def getPendingRequests(sampleGuid: UUID): Future[Seq[MatchRequestTracking]] =
    requestRepo.findPendingForSample(sampleGuid)

  override def getSentRequests(did: String): Future[Seq[MatchRequestTracking]] =
    requestRepo.findSentByDid(did)

  override def cancelRequest(atUri: String): Future[Boolean] =
    requestRepo.updateStatus(atUri, "CANCELLED")

  // --- Consent ---

  override def trackConsent(consent: MatchConsentTracking): Future[MatchConsentTracking] =
    consentRepo.upsertFromFirehose(consent)

  override def getConsentStatus(requestUri: String): Future[Option[ConsentStatus]] = {
    requestRepo.findByAtUri(requestUri).flatMap {
      case None => Future.successful(None)
      case Some(request) =>
        for {
          fromConsent <- consentRepo.findActiveConsentForSample(request.fromSampleGuid)
          toConsent <- consentRepo.findActiveConsentForSample(request.toSampleGuid)
        } yield {
          val requesterConsented = fromConsent.isDefined
          val targetConsented = toConsent.isDefined
          Some(ConsentStatus(
            requestUri = requestUri,
            requesterConsented = requesterConsented,
            targetConsented = targetConsented,
            mutualConsent = requesterConsented && targetConsented
          ))
        }
    }
  }

  override def revokeConsent(atUri: String): Future[Boolean] =
    consentRepo.revoke(atUri)
}
