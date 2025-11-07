package models.api.genomics

import play.api.libs.json.{Json, OFormat}

/**
 * Response model for the lab-instrument association operation.
 *
 * @param instrumentId The instrument ID that was associated
 * @param labId        The ID of the lab (newly created or existing)
 * @param labName      The name of the associated lab
 * @param manufacturer The manufacturer name of the instrument
 * @param model        The model name of the instrument
 * @param isNewLab     Whether a new lab placeholder was created
 * @param message      Status message describing the result
 */
case class AssociateLabWithInstrumentResponse(
                                               instrumentId: String,
                                               labId: Int,
                                               labName: String,
                                               manufacturer: Option[String] = None,
                                               model: Option[String] = None,
                                               isNewLab: Boolean,
                                               message: String
                                             )

object AssociateLabWithInstrumentResponse {
  implicit val format: OFormat[AssociateLabWithInstrumentResponse] = Json.format[AssociateLabWithInstrumentResponse]
}