package models.domain.genomics

import java.time.LocalDateTime

case class SequenceFileChecksum(
                                 id: Option[Int],
                                 sequenceFileId: Int,
                                 checksum: String,
                                 algorithm: String,
                                 verifiedAt: LocalDateTime
                               )

