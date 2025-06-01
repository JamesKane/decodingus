package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Request model for creating a PGP biosample.
 *
 * @param participantId The unique identifier of the PGP participant
 * @param description   Detailed description of the sample, including sequencing center and other metadata
 * @param centerName    The name of the PGP center (e.g., "PGP Harvard", "PGP UK") that manages the participant
 * @param sex          Optional biological sex of the participant
 * @param latitude     Optional latitude coordinate of the sample's origin
 * @param longitude    Optional longitude coordinate of the sample's origin
 */
case class PgpBiosampleRequest(
                                participantId: String,
                                description: String,
                                centerName: String,
                                sex: Option[String] = None,
                                latitude: Option[Double] = None,
                                longitude: Option[Double] = None
                              )


object PgpBiosampleRequest {
  implicit val format: OFormat[PgpBiosampleRequest] = Json.format
}
