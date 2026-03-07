package models.domain.ibd

import java.time.ZonedDateTime
import java.util.UUID
import play.api.libs.json.JsValue

case class MatchRequestTracking(
                                 id: Option[Long],
                                 atUri: String,
                                 requesterDid: String,
                                 targetDid: Option[String],
                                 fromSampleGuid: UUID,
                                 toSampleGuid: UUID,
                                 requestType: String,
                                 status: String,
                                 discoveryReason: Option[JsValue],
                                 message: Option[String],
                                 createdAt: ZonedDateTime,
                                 updatedAt: ZonedDateTime,
                                 expiresAt: Option[ZonedDateTime],
                                 completedAt: Option[ZonedDateTime]
                               )
