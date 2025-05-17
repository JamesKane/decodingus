package models.dal

import models.HaplogroupType

object DatabaseSchema {
  import models.dal.MyPostgresProfile.api.*

  implicit val haplogroupTypeMapper: BaseColumnType[HaplogroupType] =
    MappedColumnType.base[HaplogroupType, String](
      ht => ht.toString, // to database (uses the enum's toString)
      str => HaplogroupType.fromString(str).getOrElse(
        throw new IllegalArgumentException(s"Invalid haplogroup type: $str")
      ) // from database
    )

  val analysisMethods = TableQuery[AnalysisMethodTable]
  val ancestryAnalyses = TableQuery[AncestryAnalysisTable]
  val biosamples = TableQuery[BiosamplesTable]
  val biosampleHaplogroups = TableQuery[BiosampleHaplogroupsTable]
  val citizenBiosamples = TableQuery[CitizenBiosamplesTable]
  val enaStudies = TableQuery[EnaStudiesTable]
  val genbankContigs = TableQuery[GenbankContigsTable]
  val haplogroups = TableQuery[HaplogroupsTable]
  val haplogroupRelationships = TableQuery[HaplogroupRelationshipsTable]
  val haplogroupVariants = TableQuery[HaplogroupVariantsTable]
  val haplogroupVariantMetadata = TableQuery[HaplogroupVariantMetadataTable]
  val pgpBiosamples = TableQuery[PgpBiosamplesTable]
  val populations = TableQuery[PopulationsTable]
  val publications = TableQuery[PublicationsTable]
  val publicationBiosamples = TableQuery[PublicationBiosamplesTable]
  val publicationEnaStudies = TableQuery[PublicationEnaStudiesTable]
  val qualityMetrics = TableQuery[QualityMetricsTable]
  val relationshipRevisionMetadata = TableQuery[RelationshipRevisionMetadataTable]
  val reportedNegativeVariants = TableQuery[ReportedNegativeVariantsTable]
  val reportedVariants = TableQuery[ReportedVariantsTable]
  val sequenceAtpLocations = TableQuery[SequenceAtpLocationTable]
  val sequenceFiles = TableQuery[SequenceFilesTable]
  val sequenceHttpLocations = TableQuery[SequenceHttpLocationTable]
  val sequenceLibraries = TableQuery[SequenceLibrariesTable]
  val specimenDonors = TableQuery[SpecimenDonorsTable]
  val variants = TableQuery[VariantsTable]
}