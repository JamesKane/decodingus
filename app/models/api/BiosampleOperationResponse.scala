package models.api

import play.api.libs.json.{Json, OFormat}
import java.util.UUID

case class BiosampleOperationResponse(status: String, guid: UUID)

object BiosampleOperationResponse {
  implicit val format: OFormat[BiosampleOperationResponse] = Json.format[BiosampleOperationResponse]
}
