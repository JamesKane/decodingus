package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{PcaCoordinatesJsonb, PopulationBreakdown}

import java.time.LocalDateTime
import java.util.UUID

/**
 * Slick table definition for population_breakdown table.
 * Stores ancestry analysis breakdowns using PCA projection onto reference populations.
 */
class PopulationBreakdownTable(tag: Tag) extends Table[PopulationBreakdown](tag, "population_breakdown") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def atUri = column[Option[String]]("at_uri")
  def atCid = column[Option[String]]("at_cid")
  def sampleGuid = column[UUID]("sample_guid")
  def analysisMethod = column[String]("analysis_method")
  def panelType = column[Option[String]]("panel_type")
  def referencePopulations = column[Option[String]]("reference_populations")
  def snpsAnalyzed = column[Option[Int]]("snps_analyzed")
  def snpsWithGenotype = column[Option[Int]]("snps_with_genotype")
  def snpsMissing = column[Option[Int]]("snps_missing")
  def confidenceLevel = column[Option[Double]]("confidence_level")
  def pcaCoordinates = column[Option[PcaCoordinatesJsonb]]("pca_coordinates")
  def analysisDate = column[Option[LocalDateTime]]("analysis_date")
  def pipelineVersion = column[Option[String]]("pipeline_version")
  def referenceVersion = column[Option[String]]("reference_version")
  def deleted = column[Boolean]("deleted")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  def * = (
    id.?,
    atUri,
    atCid,
    sampleGuid,
    analysisMethod,
    panelType,
    referencePopulations,
    snpsAnalyzed,
    snpsWithGenotype,
    snpsMissing,
    confidenceLevel,
    pcaCoordinates,
    analysisDate,
    pipelineVersion,
    referenceVersion,
    deleted,
    createdAt,
    updatedAt
  ).mapTo[PopulationBreakdown]

  // Unique index on at_uri
  def atUriIdx = index("idx_population_breakdown_at_uri", atUri, unique = true)
  def sampleGuidIdx = index("idx_population_breakdown_sample_guid", sampleGuid)
}
