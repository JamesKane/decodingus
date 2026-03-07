package controllers

import actions.PdsAuthAction
import jakarta.inject.{Inject, Singleton}
import models.domain.ibd.{MatchConsentTracking, MatchRequestTracking}
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.*
import services.ibd.MatchDiscoveryService

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MatchRequestController @Inject()(
  val controllerComponents: ControllerComponents,
  pdsAuth: PdsAuthAction,
  discoveryService: MatchDiscoveryService
)(implicit ec: ExecutionContext) extends BaseController with Logging {

  case class MatchRequestPayload(
    fromSampleGuid: UUID,
    toSampleGuid: UUID,
    message: Option[String] = None,
    expiresInDays: Option[Int] = None
  )
  object MatchRequestPayload { implicit val format: OFormat[MatchRequestPayload] = Json.format }

  def createRequest(): Action[MatchRequestPayload] = pdsAuth.jsonAction[MatchRequestPayload].async { request =>
    val node = request.pdsNode
    val payload = request.body
    val now = ZonedDateTime.now()
    val atUri = s"at://${node.did}/us.decoding.matching.matchRequest/${UUID.randomUUID()}"

    val tracking = MatchRequestTracking(
      id = None,
      atUri = atUri,
      requesterDid = node.did,
      fromSampleGuid = payload.fromSampleGuid,
      toSampleGuid = payload.toSampleGuid,
      status = "PENDING",
      message = payload.message,
      createdAt = now,
      updatedAt = now,
      expiresAt = payload.expiresInDays.map(d => now.plusDays(d.toLong))
    )

    discoveryService.createMatchRequest(tracking).map { created =>
      Created(Json.obj(
        "atUri" -> created.atUri,
        "fromSampleGuid" -> created.fromSampleGuid,
        "toSampleGuid" -> created.toSampleGuid,
        "status" -> created.status,
        "createdAt" -> created.createdAt,
        "expiresAt" -> created.expiresAt
      ))
    }
  }

  def getPendingRequests(): Action[AnyContent] = pdsAuth.async { request =>
    request.getQueryString("sampleGuid") match {
      case Some(guidStr) =>
        val sampleGuid = UUID.fromString(guidStr)
        discoveryService.getPendingRequests(sampleGuid).map { requests =>
          Ok(Json.toJson(requests.map(requestToJson)))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "sampleGuid query parameter required")))
    }
  }

  def getSentRequests(): Action[AnyContent] = pdsAuth.async { request =>
    val did = request.pdsNode.did
    discoveryService.getSentRequests(did).map { requests =>
      Ok(Json.toJson(requests.map(requestToJson)))
    }
  }

  def cancelRequest(uri: String): Action[AnyContent] = pdsAuth.async { _ =>
    discoveryService.cancelRequest(uri).map { success =>
      Ok(Json.obj("success" -> success))
    }
  }

  // --- Consent endpoints ---

  case class ConsentPayload(
    sampleGuid: UUID,
    consentLevel: String,
    allowedMatchTypes: Option[Seq[String]] = None,
    shareContactInfo: Option[Boolean] = None,
    expiresInDays: Option[Int] = None
  )
  object ConsentPayload { implicit val format: OFormat[ConsentPayload] = Json.format }

  def submitConsent(): Action[ConsentPayload] = pdsAuth.jsonAction[ConsentPayload].async { request =>
    val node = request.pdsNode
    val payload = request.body
    val now = ZonedDateTime.now()
    val atUri = s"at://${node.did}/us.decoding.matching.matchConsent/${UUID.randomUUID()}"

    val consent = MatchConsentTracking(
      id = None,
      atUri = atUri,
      consentingDid = node.did,
      sampleGuid = payload.sampleGuid,
      consentLevel = payload.consentLevel,
      allowedMatchTypes = payload.allowedMatchTypes.map(t => Json.toJson(t)),
      shareContactInfo = payload.shareContactInfo.getOrElse(false),
      consentedAt = now,
      expiresAt = payload.expiresInDays.map(d => now.plusDays(d.toLong)),
      revokedAt = None
    )

    discoveryService.trackConsent(consent).map { created =>
      Created(Json.obj(
        "atUri" -> created.atUri,
        "sampleGuid" -> created.sampleGuid,
        "consentLevel" -> created.consentLevel,
        "consentedAt" -> created.consentedAt
      ))
    }
  }

  def getConsentStatus(requestUri: String): Action[AnyContent] = pdsAuth.async { _ =>
    discoveryService.getConsentStatus(requestUri).map {
      case Some(status) => Ok(Json.toJson(status))
      case None => NotFound(Json.obj("error" -> s"Match request not found: $requestUri"))
    }
  }

  private def requestToJson(r: MatchRequestTracking) = Json.obj(
    "atUri" -> r.atUri,
    "requesterDid" -> r.requesterDid,
    "fromSampleGuid" -> r.fromSampleGuid,
    "toSampleGuid" -> r.toSampleGuid,
    "status" -> r.status,
    "message" -> r.message,
    "createdAt" -> r.createdAt,
    "updatedAt" -> r.updatedAt,
    "expiresAt" -> r.expiresAt
  )
}
