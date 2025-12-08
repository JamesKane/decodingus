package models.domain.genomics

import java.time.LocalDateTime
import models.domain.genomics.{SequenceFileAtpLocationJsonb, SequenceFileChecksumJsonb, SequenceFileHttpLocationJsonb}

/**
 * Represents a sequence file with metadata about its library association, format, alignment information,
 * and timestamps for creation and updates.
 *
 * @param id              An optional unique identifier for the sequence file, typically used for internal purposes.
 * @param libraryId       The identifier of the library to which this sequence file belongs.
 * @param fileName        The name of the file.
 * @param fileSizeBytes   The size of the file in bytes.
 * @param fileFormat      The format of the file (e.g., FASTQ, BAM, etc.).
 * @param checksums       A list of checksums associated with the file in JSONB format.
 * @param httpLocations   A list of HTTP locations where the file can be accessed, in JSONB format.
 * @param atpLocation     An optional AT Protocol location for the file, in JSONB format.
 * @param aligner         The name of the aligner tool used for processing the sequence data.
 * @param targetReference The reference genome or target against which the sequence data was aligned.
 * @param createdAt       The timestamp when the sequence file was created in the system.
 * @param updatedAt       An optional timestamp indicating the last update time for the sequence file.
 */
case class SequenceFile(
                         id: Option[Int],
                         libraryId: Int,
                         fileName: String,
                         fileSizeBytes: Long,
                         fileFormat: String,
                         checksums: List[SequenceFileChecksumJsonb],
                         httpLocations: List[SequenceFileHttpLocationJsonb],
                         atpLocation: Option[SequenceFileAtpLocationJsonb],
                         aligner: String,
                         targetReference: String,
                         createdAt: LocalDateTime,
                         updatedAt: Option[LocalDateTime],
                       )
