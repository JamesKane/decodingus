package models.domain.publications

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID
import play.api.libs.json.{JsValue, Json, OFormat}

case class PublicationCandidate(
                                 id: Option[Int],
                                 openAlexId: String,
                                 doi: Option[String],
                                 title: String,
                                 `abstract`: Option[String],
                                 publicationDate: Option[LocalDate],
                                 journalName: Option[String],
                                 relevanceScore: Option[Double],
                                 discoveryDate: LocalDateTime,
                                 status: String, // pending, accepted, rejected, deferred
                                 reviewedBy: Option[UUID],
                                 reviewedAt: Option[LocalDateTime],
                                 rejectionReason: Option[String],
                                 rawMetadata: Option[JsValue]
                               )

object PublicationCandidate {
  implicit val format: OFormat[PublicationCandidate] = Json.format[PublicationCandidate]
}
