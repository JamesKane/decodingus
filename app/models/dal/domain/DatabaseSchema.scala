package models.dal.domain

import models.HaplogroupType
import models.dal.*
import models.dal.auth.*

/**
 * Provides the database schema definition, which includes table queries for various domain-specific entities.
 *
 * This object serves as an entry point for interacting with the database, defining all table queries using Slick's
 * `TableQuery` mechanism. Through these queries, operations such as retrieval, insertion, and modifications can
 * be performed on the corresponding database tables. Additionally, custom mappers or implicit conversions can be
 * defined here.
 *
 * Key functionalities:
 * - Defines the mapping of domain entities to database tables using Slick's `TableQuery`.
 * - Provides an implicit mapper (`haplogroupTypeMapper`) for converting `HaplogroupType` values to a
 * database-compatible string format and vice versa.
 *
 * Table queries defined in this schema include:
 * - Analysis methods
 * - Biosamples and related metadata
 * - Ancestry analyses
 * - Haplogroups, variants, and their relationships
 * - Population and publication records
 * - Quality metrics
 * - Sequence files, libraries, and locations
 * - Specimen donors
 * - Studies and reported variants
 *
 * These queries enable streamlined interaction with the database while maintaining a clear separation between
 * the database schema and application logic.
 */
object DatabaseSchema {

  import models.dal.MyPostgresProfile.api.*

  implicit val haplogroupTypeMapper: BaseColumnType[HaplogroupType] =
    MappedColumnType.base[HaplogroupType, String](
      ht => ht.toString,
      str => HaplogroupType.fromString(str).getOrElse(
        throw new IllegalArgumentException(s"Invalid haplogroup type: $str")
      )
    )


  val biosamples = TableQuery[BiosamplesTable]
  val enaStudies = TableQuery[EnaStudiesTable]
  val genbankContigs = TableQuery[GenbankContigsTable]
  val haplogroups = TableQuery[HaplogroupsTable]
  val haplogroupRelationships = TableQuery[HaplogroupRelationshipsTable]
  val haplogroupVariants = TableQuery[HaplogroupVariantsTable]
  val haplogroupVariantMetadata = TableQuery[HaplogroupVariantMetadataTable]
  val publications = TableQuery[PublicationsTable]
  val publicationBiosamples = TableQuery[PublicationBiosamplesTable]
  val publicationEnaStudies = TableQuery[PublicationEnaStudiesTable]
  val relationshipRevisionMetadata = TableQuery[RelationshipRevisionMetadataTable]
  val variants = TableQuery[VariantsTable]

  object domain {
    val analysisMethods = TableQuery[AnalysisMethodTable]
    val ancestryAnalyses = TableQuery[AncestryAnalysisTable]
    val biosampleHaplogroups = TableQuery[BiosampleHaplogroupsTable]
    val canonicalPangenomeVariants = TableQuery[CanonicalPangenomeVariantsTable]
    val citizenBiosamples = TableQuery[CitizenBiosamplesTable]
    val pgpBiosamples = TableQuery[PgpBiosamplesTable]
    val populations = TableQuery[PopulationsTable]
    val sequenceAtpLocations = TableQuery[SequenceAtpLocationTable]
    val sequenceFiles = TableQuery[SequenceFilesTable]
    val sequenceHttpLocations = TableQuery[SequenceHttpLocationTable]
    val sequenceLibraries = TableQuery[SequenceLibrariesTable]
    val specimenDonors = TableQuery[SpecimenDonorsTable]
  }

  object auth {
    val atProtocolClientMetadata = TableQuery[ATProtocolClientMetadataTable]
    val atProtocolAuthorizationServers = TableQuery[ATProtocolAuthorizationServersTable]
    val permissions = TableQuery[PermissionsTable]
    val roles = TableQuery[RolesTable]
    val userLoginInfos = TableQuery[UserLoginInfoTable]
    val userOauth2Infos = TableQuery[UserOauth2InfoTable]
    val userRoles = TableQuery[UserRolesTable]
  }
}