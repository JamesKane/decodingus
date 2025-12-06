package models.api

import play.api.libs.json.{Json, OFormat}
import java.util.UUID

case class ProjectRequest(
                           name: String,
                           description: Option[String] = None,
                           atUri: Option[String] = None,
                           atCid: Option[String] = None
                         )

object ProjectRequest {
  implicit val format: OFormat[ProjectRequest] = Json.format
}
