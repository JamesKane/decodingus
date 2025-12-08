package models.domain.publications

import java.time.LocalDateTime
import play.api.libs.json.{Json, OFormat}

case class PublicationSearchRun(
                                 id: Option[Int],
                                 configId: Int,
                                 runAt: LocalDateTime,
                                 candidatesFound: Int,
                                 newCandidates: Int,
                                 queryUsed: Option[String],
                                 durationMs: Option[Int]
                               )

object PublicationSearchRun {
  implicit val format: OFormat[PublicationSearchRun] = Json.format[PublicationSearchRun]
}
