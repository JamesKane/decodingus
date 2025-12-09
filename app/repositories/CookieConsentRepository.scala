package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.auth.CookieConsent
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CookieConsentRepository @Inject()(
    protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  private val cookieConsents = DatabaseSchema.auth.cookieConsents

  /**
   * Records a new cookie consent.
   */
  def create(consent: CookieConsent): Future[CookieConsent] = {
    val consentWithId = consent.copy(id = Some(consent.id.getOrElse(UUID.randomUUID())))
    db.run((cookieConsents returning cookieConsents) += consentWithId)
  }

  /**
   * Finds the most recent consent for a logged-in user.
   */
  def findByUserId(userId: UUID): Future[Option[CookieConsent]] = {
    db.run(
      cookieConsents
        .filter(_.userId === userId)
        .sortBy(_.consentTimestamp.desc)
        .result
        .headOption
    )
  }

  /**
   * Finds the most recent consent for a session (anonymous user).
   */
  def findBySessionId(sessionId: String): Future[Option[CookieConsent]] = {
    db.run(
      cookieConsents
        .filter(_.sessionId === sessionId)
        .sortBy(_.consentTimestamp.desc)
        .result
        .headOption
    )
  }

  /**
   * Checks if a user has given consent for the current policy version.
   */
  def hasValidConsent(userId: UUID, policyVersion: String): Future[Boolean] = {
    db.run(
      cookieConsents
        .filter(c => c.userId === userId && c.policyVersion === policyVersion && c.consentGiven)
        .exists
        .result
    )
  }

  /**
   * Checks if a session has given consent for the current policy version.
   */
  def hasValidConsentBySession(sessionId: String, policyVersion: String): Future[Boolean] = {
    db.run(
      cookieConsents
        .filter(c => c.sessionId === sessionId && c.policyVersion === policyVersion && c.consentGiven)
        .exists
        .result
    )
  }

  /**
   * Links anonymous session consent to a user after login.
   */
  def linkSessionToUser(sessionId: String, userId: UUID): Future[Int] = {
    db.run(
      cookieConsents
        .filter(c => c.sessionId === sessionId && c.userId.isEmpty)
        .map(_.userId)
        .update(Some(userId))
    )
  }
}
