package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

/**
 * Represents a specific sequencing instrument used by a laboratory.
 *
 * @param id           Unique identifier for the instrument record
 * @param instrumentId The instrument ID found in BAM/CRAM read headers (e.g., 'A00123')
 * @param labId        Foreign key to the sequencing lab
 * @param manufacturer Optional manufacturer name (e.g., 'Illumina', 'PacBio')
 * @param model        Optional model name (e.g., 'NovaSeq 6000', 'MiSeq')
 * @param createdAt    Timestamp when the record was created
 * @param updatedAt    Timestamp when the record was last updated
 */
case class SequencerInstrument(
                                id: Option[Int] = None,
                                instrumentId: String,
                                labId: Int,
                                manufacturer: Option[String] = None,
                                model: Option[String] = None,
                                createdAt: LocalDateTime = LocalDateTime.now(),
                                updatedAt: Option[LocalDateTime] = None
                              )

object SequencerInstrument {
  implicit val format: OFormat[SequencerInstrument] = Json.format[SequencerInstrument]
}