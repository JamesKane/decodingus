package models

import java.time.LocalDateTime

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
