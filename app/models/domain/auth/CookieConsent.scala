package models.domain.auth

import java.time.LocalDateTime
import java.util.UUID

/**
 * Tracks user acceptance of cookie policy for GDPR compliance.
 *
 * @param id               UUID Primary Key
 * @param userId           Optional - linked user if logged in
 * @param sessionId        Optional - session identifier for anonymous users
 * @param ipAddressHash    SHA-256 hash of IP for anonymous consent verification
 * @param consentGiven     Whether consent was given
 * @param consentTimestamp When consent was recorded
 * @param policyVersion    Version of cookie policy accepted
 * @param userAgent        Browser user agent string
 * @param createdAt        Record creation timestamp
 */
case class CookieConsent(
    id: Option[UUID],
    userId: Option[UUID],
    sessionId: Option[String],
    ipAddressHash: Option[String],
    consentGiven: Boolean,
    consentTimestamp: LocalDateTime,
    policyVersion: String,
    userAgent: Option[String],
    createdAt: LocalDateTime
)

object CookieConsent {
  val CurrentPolicyVersion = "1.0"
}
