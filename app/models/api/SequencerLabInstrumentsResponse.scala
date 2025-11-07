package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Response model for the list of lab-instrument associations.
 *
 * @param data  List of lab-instrument associations
 * @param count Total count of associations
 */
case class SequencerLabInstrumentsResponse(
                                            data: Seq[SequencerLabInfo],
                                            count: Int
                                          )

object SequencerLabInstrumentsResponse {
  implicit val format: OFormat[SequencerLabInstrumentsResponse] = Json.format[SequencerLabInstrumentsResponse]
}