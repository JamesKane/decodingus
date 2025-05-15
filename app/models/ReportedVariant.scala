package models

import java.time.LocalDateTime
import java.util.UUID

case class ReportedVariant(
                            id: Option[Long],
                            sampleGuid: UUID,
                            genbankContigId: Int,
                            position: Int,
                            referenceAllele: String,
                            alternateAllele: String,
                            variantType: String,
                            reportedDate: LocalDateTime,
                            provenance: String,
                            confidenceScore: Double,
                            notes: Option[String],
                            status: String,
                          )
