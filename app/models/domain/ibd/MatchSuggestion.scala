package models.domain.ibd

import java.time.ZonedDateTime
import java.util.UUID
import play.api.libs.json.JsValue

case class MatchSuggestion(
                             id: Option[Long],
                             targetSampleGuid: UUID,
                             suggestedSampleGuid: UUID,
                             suggestionType: String,
                             score: Double,
                             metadata: Option[JsValue],
                             status: String,
                             createdAt: ZonedDateTime,
                             expiresAt: Option[ZonedDateTime]
                           )
