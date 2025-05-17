package models

import java.time.LocalDateTime

/**
 * Represents a sequence file with metadata about its library association, format, alignment information,
 * and timestamps for creation and updates.
 *
 * @param id              An optional unique identifier for the sequence file, typically used for internal purposes.
 * @param libraryId       The identifier of the library to which this sequence file belongs.
 * @param fileName        The name of the file.
 * @param fileSizeBytes   The size of the file in bytes.
 * @param fileMd5         The MD5 checksum of the file, used to verify data integrity.
 * @param fileFormat      The format of the file (e.g., FASTQ, BAM, etc.).
 * @param aligner         The name of the aligner tool used for processing the sequence data.
 * @param targetReference The reference genome or target against which the sequence data was aligned.
 * @param created_at      The timestamp when the sequence file was created in the system.
 * @param updated_at      An optional timestamp indicating the last update time for the sequence file.
 */
case class SequenceFile(
                         id: Option[Int],
                         libraryId: Int,
                         fileName: String,
                         fileSizeBytes: Long,
                         fileMd5: String,
                         fileFormat: String,
                         aligner: String,
                         targetReference: String,
                         created_at: LocalDateTime,
                         updated_at: Option[LocalDateTime],
                       )
