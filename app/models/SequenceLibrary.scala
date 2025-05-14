package models

import java.time.LocalDateTime
import java.util.UUID

case class SequenceLibrary(
                            id: Option[Int],
                            sampleGuid: UUID,
                            lab: String,
                            testType: String,
                            runDate: LocalDateTime,
                            instrument: String,
                            reads: Int,
                            readLength: Int,
                            pairedEnd: Boolean,
                            insertSize: Option[Int],
                            created_at: LocalDateTime,
                            updated_at: Option[LocalDateTime],
                          )
