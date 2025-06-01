package models.api

import play.api.libs.json.{Json, OFormat}

case class PgpBiosampleRequest(
                                participantId: String,
                                sampleDid: String,
                                description: String,
                                centerName: String,
                                sex: Option[String] = None
                              )

object PgpBiosampleRequest {
  implicit val format: OFormat[PgpBiosampleRequest] = Json.format
}
