package models.dal

import models.HaplogroupType
import models.dal.auth.*
import models.dal.domain.*

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

  object domain {
    import models.dal.domain.user.*
    import models.dal.domain.genomics.*
    import models.dal.domain.haplogroups.*
    import models.dal.domain.pangenome.*
    import models.dal.domain.ibd.*
    import models.dal.domain.publications.*

    object user {
      val users = TableQuery[UsersTable]
      val userPdsInfos = TableQuery[UserPdsInfoTable]
      val reputationEvents = TableQuery[ReputationEventsTable]
      val reputationEventTypes = TableQuery[ReputationEventTypesTable]
      val userReputationScores = TableQuery[UserReputationScoresTable]
    }

    object genomics {
      val analysisMethods = TableQuery[AnalysisMethodTable]
      val ancestryAnalyses = TableQuery[AncestryAnalysisTable]
      val assemblyMetadata = TableQuery[AssemblyMetadataTable]
      val biosampleHaplogroups = TableQuery[BiosampleHaplogroupsTable]
      val biosamples = TableQuery[BiosamplesTable]
      val genbankContigs = TableQuery[GenbankContigsTable]
      val geneAnnotations = TableQuery[GeneAnnotationsTable]
      val populations = TableQuery[PopulationsTable]
      val sequenceAtpLocations = TableQuery[SequenceAtpLocationTable]
      val sequenceFiles = TableQuery[SequenceFilesTable]
      val sequenceFileChecksums = TableQuery[SequenceFileChecksumTable]
      val sequenceHttpLocations = TableQuery[SequenceHttpLocationTable]
      val sequenceLibraries = TableQuery[SequenceLibrariesTable]
      val specimenDonors = TableQuery[SpecimenDonorsTable]
      val validationServices = TableQuery[ValidationServicesTable]
      val variants = TableQuery[VariantsTable]
    }

    object haplogroups {
      val haplogroupRelationships = TableQuery[HaplogroupRelationshipsTable]
      val relationshipRevisionMetadata = TableQuery[RelationshipRevisionMetadataTable]
      val haplogroups = TableQuery[HaplogroupsTable]
      val haplogroupVariantMetadata = TableQuery[HaplogroupVariantMetadataTable]
      val haplogroupVariants = TableQuery[HaplogroupVariantsTable]
    }

    object pangenome {
      val canonicalPangenomeVariants = TableQuery[CanonicalPangenomeVariantsTable]
      val pangenomeAlignmentCoverages = TableQuery[PangenomeAlignmentCoverageTable]
      val pangenomeAlignmentMetadata = TableQuery[PangenomeAlignmentMetadataTable]
      val pangenomeEdges = TableQuery[PangenomeEdgesTable]
      val pangenomeGraphs = TableQuery[PangenomeGraphsTable]
      val pangenomeNodes = TableQuery[PangenomeNodesTable]
      val pangenomePathsTable = TableQuery[PangenomePathsTable]
      val pangenomeVariantLinks = TableQuery[PangenomeVariantLinksTable]
      val reportedVariantPangenomesTable = TableQuery[ReportedVariantPangenomesTable]
    }

    object publications {
      val genomicStudies = TableQuery[GenomicStudiesTable]
      val publications = TableQuery[PublicationsTable]
      val publicationBiosamples = TableQuery[PublicationBiosamplesTable]
      val publicationGenomicStudies = TableQuery[PublicationEnaStudiesTable]
      val biosampleOriginalHaplogroups = TableQuery[BiosampleOriginalHaplogroupTable]
    }

    object ibd {
      val ibdDiscoveryIndices = TableQuery[IbdDiscoveryIndicesTable]
      val ibdPdsAttestationsTable = TableQuery[IbdPdsAttestationsTable]
    }
  }

  object auth {
    val atProtocolClientMetadata = TableQuery[ATProtocolClientMetadataTable]
    val atProtocolAuthorizationServers = TableQuery[ATProtocolAuthorizationServersTable]
    val permissions = TableQuery[PermissionsTable]
    val rolePermissionsTable = TableQuery[RolePermissionsTable]
    val roles = TableQuery[RolesTable]
    val userLoginInfos = TableQuery[UserLoginInfoTable]
    val userOauth2Infos = TableQuery[UserOauth2InfoTable]
    val userRoles = TableQuery[UserRolesTable]
  }
}