package models.dal.domain.ibd

import models.dal.MyPostgresProfile.api.*
import models.domain.ibd.MatchConsentTracking
import play.api.libs.json.JsValue

import java.time.ZonedDateTime
import java.util.UUID

class MatchConsentTrackingTable(tag: Tag) extends Table[MatchConsentTracking](tag, "match_consent_tracking") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def atUri = column[String]("at_uri")
  def consentingDid = column[String]("consenting_did")
  def sampleGuid = column[UUID]("sample_guid")
  def consentLevel = column[String]("consent_level")
  def allowedMatchTypes = column[Option[JsValue]]("allowed_match_types")
  def shareContactInfo = column[Boolean]("share_contact_info")
  def consentedAt = column[ZonedDateTime]("consented_at")
  def expiresAt = column[Option[ZonedDateTime]]("expires_at")
  def revokedAt = column[Option[ZonedDateTime]]("revoked_at")

  def * = (
    id.?,
    atUri,
    consentingDid,
    sampleGuid,
    consentLevel,
    allowedMatchTypes,
    shareContactInfo,
    consentedAt,
    expiresAt,
    revokedAt
  ).mapTo[MatchConsentTracking]
}
