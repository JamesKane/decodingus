package models.dal.auth

import models.dal.MyPostgresProfile.api.*
import models.domain.auth.CookieConsent
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

/**
 * DAL table for auth.cookie_consents - tracks cookie policy acceptance for GDPR compliance.
 */
class CookieConsentsTable(tag: Tag) extends Table[CookieConsent](tag, Some("auth"), "cookie_consents") {
  def id = column[UUID]("id", O.PrimaryKey)
  def userId = column[Option[UUID]]("user_id")
  def sessionId = column[Option[String]]("session_id")
  def ipAddressHash = column[Option[String]]("ip_address_hash")
  def consentGiven = column[Boolean]("consent_given")
  def consentTimestamp = column[LocalDateTime]("consent_timestamp")
  def policyVersion = column[String]("policy_version")
  def userAgent = column[Option[String]]("user_agent")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[CookieConsent] = (
    id.?,
    userId,
    sessionId,
    ipAddressHash,
    consentGiven,
    consentTimestamp,
    policyVersion,
    userAgent,
    createdAt
  ).mapTo[CookieConsent]
}
