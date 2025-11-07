package models.api.genomics

import play.api.libs.json.{Json, OFormat}

/**
 * Request model for associating a lab with an instrument ID.
 *
 * @param instrumentId The unique instrument ID from BAM/CRAM headers (e.g., 'A00123')
 * @param labName      The name of the sequencing lab to associate with this instrument
 * @param manufacturer Optional manufacturer name (e.g., 'Illumina', 'PacBio')
 * @param model        Optional model name (e.g., 'NovaSeq 6000', 'MiSeq')
 */
case class AssociateLabWithInstrumentRequest(
                                              instrumentId: String,
                                              labName: String,
                                              manufacturer: Option[String] = None,
                                              model: Option[String] = None
                                            )

object AssociateLabWithInstrumentRequest {
  implicit val format: OFormat[AssociateLabWithInstrumentRequest] = Json.format[AssociateLabWithInstrumentRequest]
}