package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

/**
 * Represents coverage statistics for aligned sequence data.
 * All coverage percentages are expressed as values between 0.0 and 1.0 (e.g., 0.95 = 95%).
 *
 * @param alignmentMetadataId    Foreign key to the alignment metadata
 * @param meanDepth              Mean coverage depth across the region
 * @param medianDepth            Median coverage depth across the region
 * @param percentCoverageAt1x    Percentage of bases covered at ≥1x depth
 * @param percentCoverageAt5x    Percentage of bases covered at ≥5x depth
 * @param percentCoverageAt10x   Percentage of bases covered at ≥10x depth
 * @param percentCoverageAt20x   Percentage of bases covered at ≥20x depth
 * @param percentCoverageAt30x   Percentage of bases covered at ≥30x depth
 * @param basesNoCoverage        Number of bases with zero coverage
 * @param basesLowQualityMapping Number of bases with low mapping quality
 * @param basesCallable          Number of bases that meet quality thresholds for variant calling
 * @param meanMappingQuality     Mean mapping quality across all mapped reads
 */
case class AlignmentCoverage(
                              alignmentMetadataId: Long,
                              meanDepth: Option[Double] = None,
                              medianDepth: Option[Double] = None,
                              percentCoverageAt1x: Option[Double] = None,
                              percentCoverageAt5x: Option[Double] = None,
                              percentCoverageAt10x: Option[Double] = None,
                              percentCoverageAt20x: Option[Double] = None,
                              percentCoverageAt30x: Option[Double] = None,
                              basesNoCoverage: Option[Long] = None,
                              basesLowQualityMapping: Option[Long] = None,
                              basesCallable: Option[Long] = None,
                              meanMappingQuality: Option[Double] = None
                            )

object AlignmentCoverage {
  implicit val format: OFormat[AlignmentCoverage] = Json.format[AlignmentCoverage]
}