package models.domain.genomics

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents a library of sequencing data and its associated metadata.
 *
 * @param id         An optional unique identifier for the sequencing library, typically used internally.
 * @param sampleGuid A universally unique identifier (UUID) for the sample associated with this sequencing library.
 * @param lab        The name of the laboratory that processed or generated the sequencing data.
 * @param testType   The type of test performed to generate the sequencing data (e.g., WGS, RNA-Seq).
 * @param runDate    The date and time the sequencing run was performed.
 * @param instrument The name or model of the sequencing instrument used.
 * @param reads      The number of reads generated in the sequencing run.
 * @param readLength The length of each read in base pairs.
 * @param pairedEnd  Indicates whether the sequencing data is paired-end (true) or single-end (false).
 * @param insertSize An optional median insert size for paired-end sequencing libraries, representing the distance between paired reads.
 * @param created_at The timestamp indicating when this sequencing library record was created in the system.
 * @param updated_at An optional timestamp indicating the last time this sequencing library record was updated.
 */
case class SequenceLibrary(
                            id: Option[Int],
                            sampleGuid: UUID,
                            lab: String,
                            testTypeId: Int,
                            runDate: LocalDateTime,
                            instrument: String,
                            reads: Int,
                            readLength: Int,
                            pairedEnd: Boolean,
                            insertSize: Option[Int],
                            atUri: Option[String],
                            atCid: Option[String],
                            created_at: LocalDateTime,
                            updated_at: Option[LocalDateTime],
                          )
