package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{GenotypeData, GenotypeMetrics}

import java.time.LocalDateTime
import java.util.UUID

/**
 * Slick table definition for genotype_data table.
 * Stores SNP array/chip genotype data with quality metrics and derived haplogroups.
 */
class GenotypeDataTable(tag: Tag) extends Table[GenotypeData](tag, "genotype_data") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def atUri = column[Option[String]]("at_uri")
  def atCid = column[Option[String]]("at_cid")
  def sampleGuid = column[UUID]("sample_guid")
  def testTypeId = column[Option[Int]]("test_type_id")
  def provider = column[Option[String]]("provider")
  def chipVersion = column[Option[String]]("chip_version")
  def buildVersion = column[Option[String]]("build_version")
  def sourceFileHash = column[Option[String]]("source_file_hash")
  def metrics = column[GenotypeMetrics]("metrics")
  def populationBreakdownId = column[Option[Int]]("population_breakdown_id")
  def deleted = column[Boolean]("deleted")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  def * = (
    id.?,
    atUri,
    atCid,
    sampleGuid,
    testTypeId,
    provider,
    chipVersion,
    buildVersion,
    sourceFileHash,
    metrics,
    populationBreakdownId,
    deleted,
    createdAt,
    updatedAt
  ).mapTo[GenotypeData]

  // Indexes
  def atUriIdx = index("idx_genotype_at_uri", atUri, unique = true)
  def sampleGuidIdx = index("idx_genotype_sample_guid", sampleGuid)
  def testTypeIdx = index("idx_genotype_test_type", testTypeId)
  def providerIdx = index("idx_genotype_provider", provider)

  // Foreign keys
  def testTypeFk = foreignKey(
    "fk_genotype_test_type",
    testTypeId,
    TableQuery[TestTypeTable]
  )(_.id.?, onDelete = ForeignKeyAction.Restrict)

  def populationBreakdownFk = foreignKey(
    "fk_genotype_population_breakdown",
    populationBreakdownId,
    TableQuery[PopulationBreakdownTable]
  )(_.id.?, onDelete = ForeignKeyAction.SetNull)
}
