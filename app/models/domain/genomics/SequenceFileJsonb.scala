package models.domain.genomics

import java.time.LocalDateTime

case class SequenceFileChecksumJsonb(
                                        checksum: String,
                                        algorithm: String,
                                        verifiedAt: Option[LocalDateTime],
                                        createdAt: LocalDateTime,
                                        updatedAt: LocalDateTime
                                    )

case class SequenceFileHttpLocationJsonb(
                                            url: String,
                                            urlHash: String,
                                            createdAt: LocalDateTime,
                                            updatedAt: LocalDateTime
                                        )

case class SequenceFileAtpLocationJsonb(
                                           repoDid: String,
                                           recordUri: String,
                                           cid: String,
                                           createdAt: LocalDateTime,
                                           updatedAt: LocalDateTime
                                       )
