package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

case class CoverageExpectationProfile(
                                       id: Option[Int] = None,
                                       testTypeId: Int,
                                       contigName: String,
                                       variantClass: String = "SNP",
                                       minDepthHigh: Double,
                                       minDepthMedium: Double,
                                       minDepthLow: Double,
                                       minCoveragePct: Option[Double] = None,
                                       minMappingQuality: Option[Double] = None,
                                       minCallablePct: Option[Double] = None,
                                       notes: Option[String] = None,
                                       createdAt: LocalDateTime = LocalDateTime.now(),
                                       updatedAt: LocalDateTime = LocalDateTime.now()
                                     ) {

  def confidenceForDepth(actualDepth: Double): String = {
    if (actualDepth >= minDepthHigh) "high"
    else if (actualDepth >= minDepthMedium) "medium"
    else if (actualDepth >= minDepthLow) "low"
    else "insufficient"
  }
}

object CoverageExpectationProfile {
  implicit val format: OFormat[CoverageExpectationProfile] = Json.format[CoverageExpectationProfile]
}

sealed trait VariantClass {
  def dbValue: String
}

object VariantClass {
  case object SNP extends VariantClass { val dbValue = "SNP" }
  case object STR extends VariantClass { val dbValue = "STR" }
  case object INDEL extends VariantClass { val dbValue = "INDEL" }

  def fromString(s: String): Option[VariantClass] = s.toUpperCase match {
    case "SNP" => Some(SNP)
    case "STR" => Some(STR)
    case "INDEL" => Some(INDEL)
    case _ => None
  }
}

case class VariantCallingConfidence(
                                     contigName: String,
                                     variantClass: String,
                                     depthConfidence: String,
                                     coverageAdequate: Boolean,
                                     mappingQualityAdequate: Boolean,
                                     callableBasesAdequate: Boolean,
                                     overallConfidence: String,
                                     details: Map[String, String] = Map.empty
                                   )

object VariantCallingConfidence {
  implicit val format: OFormat[VariantCallingConfidence] = Json.format[VariantCallingConfidence]

  val HIGH = "high"
  val MEDIUM = "medium"
  val LOW = "low"
  val INSUFFICIENT = "insufficient"
}

case class SampleCoverageAssessment(
                                     testTypeCode: String,
                                     testTypeDisplayName: String,
                                     isChipBased: Boolean,
                                     confidences: Seq[VariantCallingConfidence],
                                     overallConfidence: String
                                   )

object SampleCoverageAssessment {
  implicit val format: OFormat[SampleCoverageAssessment] = Json.format[SampleCoverageAssessment]
}
