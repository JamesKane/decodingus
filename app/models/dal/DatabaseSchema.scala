package models.dal

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

  object domain {

    import models.dal.domain.genomics.*
    import models.dal.domain.haplogroups.*
    import models.dal.domain.ibd.*
    import models.dal.domain.pangenome.*
    import models.dal.domain.publications.*
    import models.dal.domain.social.*
    import models.dal.domain.user.* // This needs to be here for social tables

    // User-related tables directly under domain
    val users = TableQuery[UsersTable]

    // Social-related tables within a social object
    object social {
      val userBlocks = TableQuery[UserBlocksTable]
      val conversations = TableQuery[ConversationsTable]
      val conversationParticipants = TableQuery[ConversationParticipantsTable]
      val messages = TableQuery[MessagesTable]
      val feedPosts = TableQuery[FeedPostsTable]
      val reputationEvents = TableQuery[ReputationEventsTable]
      val reputationEventTypes = TableQuery[ReputationEventTypesTable]
      val userReputationScores = TableQuery[UserReputationScoresTable]
    }

    object genomics {
      val analysisMethods = TableQuery[AnalysisMethodTable]
      val ancestryAnalyses = TableQuery[AncestryAnalysisTable]
      val alignmentMetadata = TableQuery[AlignmentMetadataTable]
      val alignmentCoverages = TableQuery[AlignmentCoverageTable]
      val assemblyMetadata = TableQuery[AssemblyMetadataTable]
      val biosampleHaplogroups = TableQuery[BiosampleHaplogroupsTable]
      val biosamples = TableQuery[BiosamplesTable]
      val citizenBiosamples = TableQuery[CitizenBiosamplesTable]
      val genbankContigs = TableQuery[GenbankContigsTable]
      val geneAnnotations = TableQuery[GeneAnnotationsTable]
      val populations = TableQuery[PopulationsTable]
      val sequenceFiles = TableQuery[SequenceFilesTable]

      val sequenceLibraries = TableQuery[SequenceLibrariesTable]
      val sequencingLabs = TableQuery[SequencingLabsTable]
      val sequencerInstruments = TableQuery[SequencerInstrumentsTable]
      val specimenDonors = TableQuery[SpecimenDonorsTable]
      val validationServices = TableQuery[ValidationServicesTable]
      val testTypeDefinition = TableQuery[TestTypeTable]

      // Consolidated variant schema (replaces variant + variant_alias)
      val variantsV2 = TableQuery[VariantV2Table]
      val haplogroupCharacterStates = TableQuery[HaplogroupCharacterStateTable]
      val branchMutations = TableQuery[BranchMutationTable]
      val biosampleVariantCalls = TableQuery[BiosampleVariantCallTable]
      val strMutationRates = TableQuery[StrMutationRateTable]

      // New tables for Atmosphere Lexicon sync
      val populationBreakdowns = TableQuery[PopulationBreakdownTable]
      val populationComponents = TableQuery[PopulationComponentTable]
      val superPopulationSummaries = TableQuery[SuperPopulationSummaryTable]
      val genotypeData = TableQuery[GenotypeDataTable]
      val haplogroupReconciliations = TableQuery[HaplogroupReconciliationTable]

      // Genome regions API tables
      val genomeRegions = TableQuery[GenomeRegionTable]
      val genomeRegionVersions = TableQuery[GenomeRegionVersionTable]
    }

    object haplogroups {
      val haplogroupRelationships = TableQuery[HaplogroupRelationshipsTable]
      val relationshipRevisionMetadata = TableQuery[RelationshipRevisionMetadataTable]
      val haplogroups = TableQuery[HaplogroupsTable]
      val haplogroupVariantMetadata = TableQuery[HaplogroupVariantMetadataTable]
      val haplogroupVariants = TableQuery[HaplogroupVariantsTable]

      // Tree Versioning System (Production/WIP)
      val changeSets = TableQuery[ChangeSetsTable]
      val treeChanges = TableQuery[TreeChangesTable]
      val changeSetComments = TableQuery[ChangeSetCommentsTable]

      // WIP Shadow Tables for staging merge changes
      val wipHaplogroups = TableQuery[WipHaplogroupTable]
      val wipRelationships = TableQuery[WipRelationshipTable]
      val wipHaplogroupVariants = TableQuery[WipHaplogroupVariantTable]
      val wipReparents = TableQuery[WipReparentTable]
      val wipResolutions = TableQuery[WipResolutionTable]
    }

    object pangenome {
      val canonicalPangenomeVariants = TableQuery[CanonicalPangenomeVariantsTable]
      val pangenomeAlignmentCoverages = TableQuery[PangenomeAlignmentCoverageTable]
      val pangenomeAlignmentMetadata = TableQuery[PangenomeAlignmentMetadataTable]
      val pangenomeGraphs = TableQuery[PangenomeGraphsTable]
      val pangenomeNodes = TableQuery[PangenomeNodesTable]
      val pangenomePathsTable = TableQuery[PangenomePathsTable]
      val pangenomeVariantLinks = TableQuery[PangenomeVariantLinksTable]
      val reportedVariantPangenomesTable = TableQuery[ReportedVariantPangenomesTable]
    }

    object publications {
      val genomicStudies = TableQuery[GenomicStudiesTable]
      val publications = TableQuery[PublicationsTable]
      val publicationCandidates = TableQuery[PublicationCandidatesTable]
      val publicationSearchConfigs = TableQuery[PublicationSearchConfigsTable]
      val publicationSearchRuns = TableQuery[PublicationSearchRunsTable]
      val publicationBiosamples = TableQuery[PublicationBiosamplesTable]
      val publicationCitizenBiosamples = TableQuery[PublicationCitizenBiosamplesTable]
      val publicationGenomicStudies = TableQuery[PublicationEnaStudiesTable]
      val biosampleOriginalHaplogroups = TableQuery[BiosampleOriginalHaplogroupTable]
      val citizenBiosampleOriginalHaplogroups = TableQuery[CitizenBiosampleOriginalHaplogroupTable]
    }

    object ibd {
      val ibdDiscoveryIndices = TableQuery[IbdDiscoveryIndicesTable]
      val ibdPdsAttestationsTable = TableQuery[IbdPdsAttestationsTable]
    }

    object project {
      val projects = TableQuery[ProjectTable]
    }
  }

  object auth {
    val atProtocolClientMetadata = TableQuery[ATProtocolClientMetadataTable]
    val atProtocolAuthorizationServers = TableQuery[ATProtocolAuthorizationServersTable]
    val cookieConsents = TableQuery[CookieConsentsTable]
    val permissions = TableQuery[PermissionsTable]
    val rolePermissionsTable = TableQuery[RolePermissionsTable]
    val roles = TableQuery[RolesTable]
    val userLoginInfos = TableQuery[UserLoginInfoTable]
    val userOauth2Infos = TableQuery[UserOauth2InfoTable]
    val userPdsInfos = TableQuery[UserPdsInfoTable]
    val userRoles = TableQuery[UserRolesTable]
  }

  object support {
    import models.dal.support.*
    val contactMessages = TableQuery[ContactMessagesTable]
    val messageReplies = TableQuery[MessageRepliesTable]
  }

  object curator {
    import models.dal.curator.*
    val auditLog = TableQuery[AuditLogTable]
  }
}