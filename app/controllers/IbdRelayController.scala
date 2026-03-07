package controllers

import jakarta.inject.{Inject, Singleton}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import play.api.Logging
import play.api.libs.json.{Json, JsValue}
import play.api.mvc.*
import services.PdsSignatureVerifier
import services.ibd.{IbdRelaySessionManager, MatchDiscoveryService, RelayMessage, RelaySession}
import repositories.PdsNodeRepository

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IbdRelayController @Inject()(
  val controllerComponents: ControllerComponents,
  sessionManager: IbdRelaySessionManager,
  signatureVerifier: PdsSignatureVerifier,
  nodeRepo: PdsNodeRepository,
  discoveryService: MatchDiscoveryService
)(implicit ec: ExecutionContext, mat: Materializer) extends BaseController with Logging {

  /**
   * WebSocket relay endpoint.
   * Auth via query params: ?did=...&timestamp=...&signature=...&nonce=...
   * WebSocket handshake is an HTTP GET, so we can't use headers in the browser/client
   * the same way as REST. Query params are the standard approach.
   */
  def relay(sessionId: String): WebSocket = WebSocket.acceptOrResult[String, String] { requestHeader =>
    val did = requestHeader.getQueryString("did")
    val timestamp = requestHeader.getQueryString("timestamp")
    val signature = requestHeader.getQueryString("signature")
    val nonce = requestHeader.getQueryString("nonce")

    (did, timestamp, signature) match {
      case (Some(d), Some(ts), Some(sig)) =>
        authenticateAndConnect(sessionId, d, ts, sig, nonce, requestHeader)
      case _ =>
        Future.successful(Left(Forbidden(Json.obj(
          "error" -> "Missing authentication parameters (did, timestamp, signature)"
        ))))
    }
  }

  /**
   * Create a relay session when mutual consent is detected.
   * Called by the Edge client after confirming mutual consent exists.
   */
  case class CreateSessionRequest(matchRequestUri: String)
  object CreateSessionRequest {
    implicit val format: play.api.libs.json.OFormat[CreateSessionRequest] = Json.format
  }

  def createSession(): Action[JsValue] = Action(parse.json).async { request =>
    // Auth via headers like other PDS endpoints
    val headers = request.headers
    val did = headers.get("X-PDS-DID")
    val sig = headers.get("X-PDS-Signature")
    val ts = headers.get("X-PDS-Timestamp")

    (did, sig, ts) match {
      case (Some(d), Some(s), Some(t)) =>
        if (!signatureVerifier.isTimestampValid(t)) {
          Future.successful(Unauthorized(Json.obj("error" -> "Timestamp expired")))
        } else {
          val bodyHash = signatureVerifier.hashBody(request)
          val signingInput = signatureVerifier.buildSigningInput("POST", request.path, t, bodyHash, headers.get("X-PDS-Nonce"))
          signatureVerifier.verifySignature(d, signingInput, s).flatMap {
            case false =>
              Future.successful(Unauthorized(Json.obj("error" -> "Invalid signature")))
            case true =>
              request.body.validate[CreateSessionRequest].fold(
                errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request body"))),
                payload => handleCreateSession(d, payload.matchRequestUri)
              )
          }
        }
      case _ =>
        Future.successful(Unauthorized(Json.obj("error" -> "Missing auth headers")))
    }
  }

  private def handleCreateSession(requesterDid: String, matchRequestUri: String): Future[Result] = {
    for {
      consentOpt <- discoveryService.getConsentStatus(matchRequestUri)
      requestOpt <- discoveryService.getMatchRequest(matchRequestUri)
    } yield {
      (consentOpt, requestOpt) match {
        case (None, _) | (_, None) =>
          NotFound(Json.obj("error" -> "Match request not found"))
        case (Some(status), _) if !status.mutualConsent =>
          Forbidden(Json.obj("error" -> "Mutual consent not established"))
        case (Some(_), Some(matchRequest)) =>
          // Check that the requester is actually a participant
          val participantA = matchRequest.requesterDid
          val participantB = matchRequest.targetDid.getOrElse("")
          if (requesterDid != participantA && requesterDid != participantB) {
            Forbidden(Json.obj("error" -> "Not a participant in this match request"))
          } else {
            sessionManager.findSessionForRequest(matchRequestUri) match {
              case Some(existing) =>
                Ok(Json.obj(
                  "sessionId" -> existing.sessionId,
                  "expiresAt" -> existing.expiresAt.toString
                ))
              case None =>
                sessionManager.createSession(matchRequestUri, participantA, participantB) match {
                  case Some(session) =>
                    Created(Json.obj(
                      "sessionId" -> session.sessionId,
                      "expiresAt" -> session.expiresAt.toString
                    ))
                  case None =>
                    ServiceUnavailable(Json.obj("error" -> "Max concurrent sessions reached"))
                }
            }
          }
      }
    }
  }

  private def authenticateAndConnect(
    sessionId: String,
    did: String,
    timestamp: String,
    signature: String,
    nonce: Option[String],
    requestHeader: RequestHeader
  ): Future[Either[Result, Flow[String, String, ?]]] = {

    // Validate timestamp
    if (!signatureVerifier.isTimestampValid(timestamp)) {
      return Future.successful(Left(Unauthorized(Json.obj("error" -> "Timestamp expired"))))
    }

    // Validate nonce
    if (nonce.exists(n => !signatureVerifier.checkAndRecordNonce(n))) {
      return Future.successful(Left(Unauthorized(Json.obj("error" -> "Nonce already used"))))
    }

    // Build signing input for WebSocket handshake (GET, no body)
    val emptyBodyHash = java.util.Base64.getEncoder.encodeToString(
      java.security.MessageDigest.getInstance("SHA-256").digest(Array.empty[Byte])
    )
    val signingInput = signatureVerifier.buildSigningInput("GET", requestHeader.path, timestamp, emptyBodyHash, nonce)

    signatureVerifier.verifySignature(did, signingInput, signature).map {
      case false =>
        Left(Unauthorized(Json.obj("error" -> "Invalid signature")))
      case true =>
        sessionManager.getSession(sessionId) match {
          case None =>
            Left(NotFound(Json.obj("error" -> "Session not found or expired")))
          case Some(session) if !sessionManager.isAuthorizedParticipant(sessionId, did) =>
            Left(Forbidden(Json.obj("error" -> "Not authorized for this session")))
          case Some(session) =>
            logger.info(s"WebSocket connected: DID=$did session=$sessionId")
            Right(createRelayFlow(session, did))
        }
    }
  }

  private def createRelayFlow(session: RelaySession, did: String): Flow[String, String, ?] = {
    // Incoming messages from this participant → publish to the bus
    val incomingSink = Flow[String]
      .map(msg => RelayMessage(fromDid = did, payload = msg))
      .to(session.bus.publishSink)

    // Outgoing messages from the bus → filtered to exclude own messages
    val outgoingSource = session.bus.subscribeTo(did)
      .map(_.payload)

    Flow.fromSinkAndSource(incomingSink, outgoingSource)
  }

  /**
   * REST endpoint to check session status (for Edge clients that need to poll).
   */
  def getSessionStatus(sessionId: String): Action[AnyContent] = Action { _ =>
    sessionManager.getSession(sessionId) match {
      case Some(session) =>
        Ok(Json.obj(
          "sessionId" -> session.sessionId,
          "matchRequestUri" -> session.matchRequestUri,
          "expiresAt" -> session.expiresAt.toString,
          "active" -> true
        ))
      case None =>
        NotFound(Json.obj("error" -> "Session not found or expired"))
    }
  }
}
