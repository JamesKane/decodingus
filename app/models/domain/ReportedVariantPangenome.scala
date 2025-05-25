package models.domain

import play.api.libs.json.JsValue

import java.time.ZonedDateTime
import java.util.UUID

case class ReportedVariantPangenome(
                                     id: Option[Long],
                                     sampleGuid: UUID,
                                     graphId: Int,
                                     variantType: String,
                                     referencePathId: Option[Int],
                                     referenceStartPosition: Option[Int],
                                     referenceEndPosition: Option[Int],
                                     variantNodes: List[Int],
                                     variantEdges: List[Int],
                                     alternateAlleleSequence: Option[String],
                                     referenceAlleleSequence: Option[String],
                                     referenceRepeatCount: Option[Int],
                                     alternateRepeatCount: Option[Int],
                                     alleleFraction: Option[Double],
                                     depth: Option[Int],
                                     reportedDate: ZonedDateTime,
                                     provenance: String,
                                     confidenceScore: Double,
                                     notes: Option[String],
                                     status: String,
                                     zygosity: Option[String],
                                     haplotypeInformation: Option[JsValue]
                                   )