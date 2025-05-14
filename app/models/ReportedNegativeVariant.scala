package models

import java.time.LocalDateTime
import java.util.UUID

case class ReportedNegativeVariant(
                                    id: Option[Long],
                                    sampleGuid: UUID,
                                    variantId: Long,
                                    reportedDate: LocalDateTime,
                                    provenance: String,
                                    notes: Option[String],
                                    status: String,
                                  )
