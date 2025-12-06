package models.api

import play.api.libs.json.{Json, OFormat}
import java.time.LocalDateTime
import java.util.UUID

case class ProjectResponse(
                            projectGuid: UUID,
                            name: String,
                            description: Option[String],
                            ownerDid: String,
                            createdAt: LocalDateTime,
                            updatedAt: LocalDateTime,
                            atCid: Option[String]
                          )

object ProjectResponse {
  implicit val format: OFormat[ProjectResponse] = Json.format
}
