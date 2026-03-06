package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

case class TestTypeTargetRegion(
                                 id: Option[Int] = None,
                                 testTypeId: Int,
                                 contigName: String,
                                 startPosition: Option[Int] = None,
                                 endPosition: Option[Int] = None,
                                 regionName: String,
                                 regionType: String,
                                 expectedCoveragePct: Option[Double] = None,
                                 expectedMinDepth: Option[Double] = None
                               ) {
  def regionSize: Option[Int] = for {
    s <- startPosition
    e <- endPosition
  } yield e - s + 1
}

object TestTypeTargetRegion {
  implicit val format: OFormat[TestTypeTargetRegion] = Json.format[TestTypeTargetRegion]
}

case class RegionCoverageResult(
                                 regionName: String,
                                 contigName: String,
                                 startPosition: Option[Int],
                                 endPosition: Option[Int],
                                 expectedCoveragePct: Option[Double],
                                 expectedMinDepth: Option[Double],
                                 actualMeanDepth: Option[Double],
                                 actualCoveragePct: Option[Double],
                                 meetsExpectation: Boolean
                               )

object RegionCoverageResult {
  implicit val format: OFormat[RegionCoverageResult] = Json.format[RegionCoverageResult]
}

case class TargetedCoverageAssessment(
                                       testTypeCode: String,
                                       testTypeDisplayName: String,
                                       targetRegions: Seq[RegionCoverageResult],
                                       overallCoveragePct: Double,
                                       overallMeetsExpectation: Boolean,
                                       qualityTier: String
                                     )

object TargetedCoverageAssessment {
  implicit val format: OFormat[TargetedCoverageAssessment] = Json.format[TargetedCoverageAssessment]

  def qualityTierFromCoverage(coveragePct: Double): String = {
    if (coveragePct >= 0.95) "HIGH"
    else if (coveragePct >= 0.80) "MEDIUM"
    else if (coveragePct >= 0.50) "LOW"
    else "INSUFFICIENT"
  }
}
