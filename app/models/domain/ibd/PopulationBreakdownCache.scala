package models.domain.ibd

import java.time.ZonedDateTime
import java.util.UUID
import play.api.libs.json.JsValue

case class PopulationBreakdownCache(
                                     id: Option[Long],
                                     sampleGuid: UUID,
                                     breakdown: JsValue,
                                     breakdownHash: String,
                                     cachedAt: ZonedDateTime,
                                     sourceAtUri: Option[String]
                                   )
