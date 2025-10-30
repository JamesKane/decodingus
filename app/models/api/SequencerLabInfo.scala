package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Response model containing sequencer instrument and lab information.
 *
 * @param instrumentId  The unique instrument ID from BAM/CRAM headers
 * @param labName       Name of the sequencing laboratory
 * @param isD2c         Whether the lab offers direct-to-consumer services
 * @param manufacturer  Optional manufacturer name (e.g., 'Illumina', 'PacBio')
 * @param model         Optional model name (e.g., 'NovaSeq 6000', 'MiSeq')
 * @param websiteUrl    Optional URL to the lab's official website
 */
case class SequencerLabInfo(
                             instrumentId: String,
                             labName: String,
                             isD2c: Boolean,
                             manufacturer: Option[String] = None,
                             model: Option[String] = None,
                             websiteUrl: Option[String] = None
                           )

object SequencerLabInfo {
  implicit val format: OFormat[SequencerLabInfo] = Json.format[SequencerLabInfo]
}