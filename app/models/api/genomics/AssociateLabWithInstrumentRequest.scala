package models.api.genomics

import play.api.libs.json.{Json, OFormat}

/**
 * Request model for associating a lab with an instrument ID.
 *
 * @param instrumentId The unique instrument ID from BAM/CRAM headers (e.g., 'A00123')
 * @param labName      The name of the sequencing lab to associate with this instrument
 */
case class AssociateLabWithInstrumentRequest(
                                              instrumentId: String,
                                              labName: String
                                            )

object AssociateLabWithInstrumentRequest {
  implicit val format: OFormat[AssociateLabWithInstrumentRequest] = Json.format[AssociateLabWithInstrumentRequest]
}