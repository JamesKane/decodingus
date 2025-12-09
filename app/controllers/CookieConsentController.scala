package controllers

import jakarta.inject.{Inject, Singleton}
import models.domain.auth.CookieConsent
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.CookieConsentRepository

import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class ConsentStatusResponse(hasConsent: Boolean, policyVersion: String)

object ConsentStatusResponse {
  implicit val format: OFormat[ConsentStatusResponse] = Json.format[ConsentStatusResponse]
}

@Singleton
class CookieConsentController @Inject()(
    val controllerComponents: ControllerComponents,
    cookieConsentRepository: CookieConsentRepository
)(implicit ec: ExecutionContext) extends BaseController with Logging {

  private val ConsentCookieName = "cookie_consent"
  private val ConsentSessionKey = "consent_session_id"

  /**
   * Check if the current user/session has given consent.
   * Returns JSON with consent status.
   */
  def checkConsent: Action[AnyContent] = Action.async { implicit request =>
    val policyVersion = CookieConsent.CurrentPolicyVersion

    // Check if user is logged in
    request.session.get("userId").map(UUID.fromString) match {
      case Some(userId) =>
        cookieConsentRepository.hasValidConsent(userId, policyVersion).map { hasConsent =>
          Ok(Json.toJson(ConsentStatusResponse(hasConsent, policyVersion)))
        }
      case None =>
        // Check session-based consent
        request.session.get(ConsentSessionKey) match {
          case Some(sessionId) =>
            cookieConsentRepository.hasValidConsentBySession(sessionId, policyVersion).map { hasConsent =>
              Ok(Json.toJson(ConsentStatusResponse(hasConsent, policyVersion)))
            }
          case None =>
            // Also check cookie for returning visitors
            request.cookies.get(ConsentCookieName) match {
              case Some(cookie) if cookie.value == policyVersion =>
                Future.successful(Ok(Json.toJson(ConsentStatusResponse(hasConsent = true, policyVersion))))
              case _ =>
                Future.successful(Ok(Json.toJson(ConsentStatusResponse(hasConsent = false, policyVersion))))
            }
        }
    }
  }

  /**
   * Record cookie consent acceptance.
   */
  def acceptConsent: Action[AnyContent] = Action.async { implicit request =>
    val policyVersion = CookieConsent.CurrentPolicyVersion
    val now = LocalDateTime.now()
    val userAgent = request.headers.get("User-Agent")
    val ipHash = hashIpAddress(request.remoteAddress)

    // Get or create session ID for anonymous users
    val sessionId = request.session.get(ConsentSessionKey).getOrElse(UUID.randomUUID().toString)

    // Check if user is logged in
    val userId = request.session.get("userId").map(UUID.fromString)

    val consent = CookieConsent(
      id = None,
      userId = userId,
      sessionId = if (userId.isEmpty) Some(sessionId) else None,
      ipAddressHash = Some(ipHash),
      consentGiven = true,
      consentTimestamp = now,
      policyVersion = policyVersion,
      userAgent = userAgent,
      createdAt = now
    )

    cookieConsentRepository.create(consent).map { _ =>
      logger.info(s"Cookie consent recorded for ${userId.map(_.toString).getOrElse(s"session:$sessionId")}")

      Ok(Json.obj("success" -> true, "message" -> "Consent recorded"))
        .withSession(request.session + (ConsentSessionKey -> sessionId))
        .withCookies(
          play.api.mvc.Cookie(
            name = ConsentCookieName,
            value = policyVersion,
            maxAge = Some(365 * 24 * 60 * 60), // 1 year
            httpOnly = false // Needs to be readable by JS for banner logic
          )
        )
    }.recover { case e: Exception =>
      logger.error("Failed to record cookie consent", e)
      InternalServerError(Json.obj("success" -> false, "message" -> "Failed to record consent"))
    }
  }

  /**
   * Hash IP address for privacy-preserving storage.
   */
  private def hashIpAddress(ip: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(ip.getBytes("UTF-8"))
    hash.map("%02x".format(_)).mkString
  }
}
