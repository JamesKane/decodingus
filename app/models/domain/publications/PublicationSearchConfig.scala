package models.domain.publications

import java.time.LocalDateTime
import play.api.libs.json.{JsValue, Json, OFormat}

case class PublicationSearchConfig(
                                    id: Option[Int],
                                    name: String,
                                    searchQuery: String,
                                    concepts: Option[JsValue],
                                    journals: Option[JsValue],
                                    enabled: Boolean,
                                    lastRun: Option[LocalDateTime],
                                    createdAt: LocalDateTime
                                  )

object PublicationSearchConfig {
  implicit val format: OFormat[PublicationSearchConfig] = Json.format[PublicationSearchConfig]
}
