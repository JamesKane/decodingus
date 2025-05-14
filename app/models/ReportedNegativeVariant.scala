package models

import java.time.LocalDateTime
import java.util.UUID

case class ReportedNegativeVariant(
                                    id: Option[Long],
                                    sampleGuid: UUID,
                                    variantId: Int,
                                    reportedDate: LocalDateTime,
                                    notes: Option[String],
                                    status: String,
                                  )
