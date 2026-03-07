package models.domain.ibd

import java.time.ZonedDateTime
import java.util.UUID

case class MatchRequestTracking(
                                 id: Option[Long],
                                 atUri: String,
                                 requesterDid: String,
                                 fromSampleGuid: UUID,
                                 toSampleGuid: UUID,
                                 status: String,
                                 message: Option[String],
                                 createdAt: ZonedDateTime,
                                 updatedAt: ZonedDateTime,
                                 expiresAt: Option[ZonedDateTime]
                               )
