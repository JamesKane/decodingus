package models.domain.ibd

import java.time.ZonedDateTime
import java.util.UUID
import play.api.libs.json.JsValue

case class MatchConsentTracking(
                                 id: Option[Long],
                                 atUri: String,
                                 consentingDid: String,
                                 sampleGuid: UUID,
                                 consentLevel: String,
                                 allowedMatchTypes: Option[JsValue],
                                 shareContactInfo: Boolean,
                                 consentedAt: ZonedDateTime,
                                 expiresAt: Option[ZonedDateTime],
                                 revokedAt: Option[ZonedDateTime]
                               )
