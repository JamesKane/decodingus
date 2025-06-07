package models.dal.domain.pangenome

import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.PangenomeAlignmentCoverage
import slick.lifted.ProvenShape

class PangenomeAlignmentCoverageTable(tag: Tag) extends Table[PangenomeAlignmentCoverage](tag, "pangenome_alignment_coverage") {
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
  ).mapTo[PangenomeAlignmentCoverage]

  def metadataFk = foreignKey(
    "fk_alignment_coverage_metadata",
    alignmentMetadataId,
    TableQuery[PangenomeAlignmentMetadataTable])(_.id, onDelete = ForeignKeyAction.Cascade)
}