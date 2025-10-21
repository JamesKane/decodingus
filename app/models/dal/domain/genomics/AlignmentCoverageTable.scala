package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.AlignmentCoverage

/**
 * Slick table definition for alignment_coverage table.
 */
class AlignmentCoverageTable(tag: Tag) extends Table[AlignmentCoverage](tag, Some("public"), "alignment_coverage") {

  def alignmentMetadataId = column[Long]("alignment_metadata_id", O.PrimaryKey)
  def meanDepth = column[Option[Double]]("mean_depth")
  def medianDepth = column[Option[Double]]("median_depth")
  def percentCoverageAt1x = column[Option[Double]]("percent_coverage_at_1x")
  def percentCoverageAt5x = column[Option[Double]]("percent_coverage_at_5x")
  def percentCoverageAt10x = column[Option[Double]]("percent_coverage_at_10x")
  def percentCoverageAt20x = column[Option[Double]]("percent_coverage_at_20x")
  def percentCoverageAt30x = column[Option[Double]]("percent_coverage_at_30x")
  def basesNoCoverage = column[Option[Long]]("bases_no_coverage")
  def basesLowQualityMapping = column[Option[Long]]("bases_low_quality_mapping")
  def basesCallable = column[Option[Long]]("bases_callable")
  def meanMappingQuality = column[Option[Double]]("mean_mapping_quality")

  def * = (
    alignmentMetadataId,
    meanDepth,
    medianDepth,
    percentCoverageAt1x,
    percentCoverageAt5x,
    percentCoverageAt10x,
    percentCoverageAt20x,
    percentCoverageAt30x,
    basesNoCoverage,
    basesLowQualityMapping,
    basesCallable,
    meanMappingQuality
  ) <> ((AlignmentCoverage.apply _).tupled, AlignmentCoverage.unapply)

  // Foreign key constraint
  def alignmentMetadataFk = foreignKey("alignment_coverage_metadata_fk", alignmentMetadataId,
    TableQuery[AlignmentMetadataTable])(_.id, onDelete = ForeignKeyAction.Cascade)
}