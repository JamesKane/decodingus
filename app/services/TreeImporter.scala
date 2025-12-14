package services

import models.*
import models.api.{TreeDTO, TreeNodeDTO, VariantDTO}
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import models.domain.haplogroups.{Haplogroup, HaplogroupRelationship, HaplogroupVariantMetadata, RelationshipRevisionMetadata}
import play.api.Logging
import play.api.libs.json.Json
import repositories.*

import java.time.LocalDateTime
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
 * Configuration class for tree import settings.
 */
case class TreeImportSettings(
  initialAuthor: String = "system",
  source: String = "initial_import",
  defaultConfidenceLevel: String = "MEDIUM",
  backboneConfidenceLevel: String = "HIGH"
)

/**
 * Class responsible for importing and processing phylogenetic tree data into the system,
 * including haplogroup information, relationships, and variant associations.
 */
class TreeImporter @Inject()(
  haplogroupRevisionRepository: HaplogroupRevisionRepository,
  haplogroupRelationshipRepository: HaplogroupRelationshipRepository,
  haplogroupVariantRepository: HaplogroupVariantRepository,
  haplogroupVariantMetadataRepository: HaplogroupVariantMetadataRepository,
  haplogroupRevisionMetadataRepository: HaplogroupRevisionMetadataRepository,
  genbankContigRepository: GenbankContigRepository,
  variantV2Repository: VariantV2Repository
)(implicit ec: ExecutionContext) extends Logging {
  private val defaultSettings = TreeImportSettings()

  /**
   * Imports a tree structure into the system by recursively processing its nodes,
   * creating haplogroups, relationships, and variants.
   */
  def importTree(tree: TreeDTO, haplogroupType: HaplogroupType)(implicit settings: TreeImportSettings = defaultSettings): Future[Unit] = {
    val timestamp = LocalDateTime.now()

    def processNode(
      node: TreeNodeDTO,
      parentId: Option[Int] = None,
      depth: Int = 0
    ): Future[Int] = {
      for {
        // 1. Create the haplogroup
        haplogroupId <- createHaplogroup(node, haplogroupType, timestamp)

        // 2. Create relationship if there's a parent
        _ <- parentId match {
          case Some(pid) => createRelationship(pid, haplogroupId, timestamp)
          case None => Future.successful(())
        }

        // 3. Create variants
        _ <- createVariants(node.variants, haplogroupId, timestamp)

        // 4. Process children recursively
        _ <- Future.sequence(
          node.children.map(child => processNode(child, Some(haplogroupId), depth + 1))
        )
      } yield haplogroupId
    }

    // Start with the root node
    tree.subclade match {
      case Some(root) => processNode(root).map(_ => ())
      case None => Future.successful(())
    }
  }

  /**
   * Creates a haplogroup entity with associated revision metadata.
   */
  private def createHaplogroup(
    node: TreeNodeDTO,
    haplogroupType: HaplogroupType,
    timestamp: LocalDateTime
  )(implicit settings: TreeImportSettings): Future[Int] = {
    logger.debug(s"Creating haplogroup: ${node.name}")
    val haplogroup = Haplogroup(
      id = None,
      name = node.name,
      lineage = None,
      description = None, // TreeNodeDTO doesn't have description
      haplogroupType = haplogroupType,
      revisionId = 1,
      source = settings.source,
      confidenceLevel = settings.defaultConfidenceLevel,
      validFrom = timestamp,
      validUntil = None
    )

    val revisionComment = s"Created during tree import from source: ${settings.source}"

    // Create the haplogroup revision
    // Note: Haplogroup revision metadata is not currently tracked separately
    haplogroupRevisionRepository.createNewRevision(haplogroup)
  }

  /**
   * Creates a relationship between parent and child haplogroups.
   */
  private def createRelationship(
    parentId: Int,
    childId: Int,
    timestamp: LocalDateTime
  )(implicit settings: TreeImportSettings): Future[Unit] = {
    logger.debug(s"Creating relationship: parent=$parentId -> child=$childId")
    val relationship = HaplogroupRelationship(
      id = None,
      childHaplogroupId = childId,
      parentHaplogroupId = parentId,
      revisionId = 1,
      validFrom = timestamp,
      validUntil = None,
      source = settings.source,
    )

    val metadata = RelationshipRevisionMetadata(
      haplogroup_relationship_id = 0, // Will be set after relationship creation
      revisionId = 1,
      author = settings.initialAuthor,
      timestamp = timestamp,
      comment = "Initial tree import",
      changeType = "CREATE",
      previousRevisionId = None
    )

    for {
      relId <- haplogroupRelationshipRepository.createRelationshipRevision(relationship)
      _ <- haplogroupRevisionMetadataRepository.addRelationshipRevisionMetadata(
        metadata.copy(haplogroup_relationship_id = relId)
      )
    } yield ()
  }

  /**
   * Creates or retrieves genetic variants and associates them with a haplogroup.
   * Now uses VariantV2 with JSONB coordinates.
   */
  private def createVariants(
    variants: Seq[VariantDTO],
    haplogroupId: Int,
    timestamp: LocalDateTime
  )(implicit settings: TreeImportSettings): Future[Unit] = {
    logger.debug(s"Starting to process ${variants.size} variants for haplogroup $haplogroupId")

    // Process variants sequentially to avoid overwhelming the connection pool
    variants.grouped(100).toSeq.foldLeft(Future.successful(())) { case (prevFuture, batch) =>
      prevFuture.flatMap { _ =>
        for {
          // Create/find variants and get their IDs
          variantIds <- Future.traverse(batch) { variantDto =>
            createOrFindVariant(variantDto)
          }
          // Associate variants with haplogroup
          _ <- Future.traverse(variantIds.flatten) { variantId =>
            createVariantAssociation(haplogroupId, variantId, timestamp)
          }
        } yield ()
      }
    }
  }

  /**
   * Creates a new VariantV2 or finds an existing one.
   */
  private def createOrFindVariant(variantDto: VariantDTO): Future[Option[Int]] = {
    // Build coordinates JSONB from DTO
    val coordinatesJson = variantDto.coordinates.foldLeft(Json.obj()) { case (acc, (contigAccession, coord)) =>
      // For now, use accession as the reference genome key
      // In a real implementation, we'd map accession to reference genome name (GRCh38, hs1, etc.)
      acc + (contigAccession -> Json.obj(
        "contig" -> contigAccession,
        "position" -> coord.start,
        "ref" -> coord.anc,
        "alt" -> coord.der
      ))
    }

    // Determine canonical name and rsId
    val isRsId = variantDto.name.startsWith("rs")
    val canonicalName = if (isRsId) None else Some(variantDto.name)
    val rsIds = if (isRsId) Seq(variantDto.name) else Seq.empty

    // Build aliases JSONB
    val aliasesJson = Json.obj(
      "common_names" -> Seq.empty[String],
      "rs_ids" -> rsIds,
      "sources" -> Json.obj("import" -> Seq(variantDto.name))
    )

    val variant = VariantV2(
      variantId = None,
      canonicalName = canonicalName,
      mutationType = MutationType.fromStringOrDefault(variantDto.variantType),
      namingStatus = if (canonicalName.isDefined) NamingStatus.Named else NamingStatus.Unnamed,
      aliases = aliasesJson,
      coordinates = coordinatesJson,
      definingHaplogroupId = None,
      evidence = Json.obj(),
      primers = Json.obj(),
      notes = None
    )

    // Try to find existing variant by name, otherwise create
    canonicalName match {
      case Some(name) =>
        variantV2Repository.findByCanonicalName(name).flatMap {
          case Some(existing) => Future.successful(existing.variantId)
          case None => variantV2Repository.create(variant).map(Some(_))
        }
      case None if rsIds.nonEmpty =>
        variantV2Repository.findByAlias(rsIds.head).flatMap { existingVariants =>
          existingVariants.headOption match {
            case Some(existing) => Future.successful(existing.variantId)
            case None => variantV2Repository.create(variant).map(Some(_))
          }
        }
      case None =>
        variantV2Repository.create(variant).map(Some(_))
    }
  }

  import scala.util.control.NonFatal

  private def createVariantAssociation(
    haplogroupId: Int,
    variantId: Int,
    timestamp: LocalDateTime
  )(implicit settings: TreeImportSettings): Future[Int] = {
    (for {
      // Create the haplogroup-variant association
      assocId <- haplogroupVariantRepository.addVariantToHaplogroup(haplogroupId, variantId)

      // Add metadata for the association
      _ <- haplogroupVariantMetadataRepository.addVariantRevisionMetadata(
        HaplogroupVariantMetadata(
          haplogroup_variant_id = assocId,
          revision_id = 1,
          author = settings.initialAuthor,
          timestamp = timestamp,
          comment = "Initial variant import",
          change_type = "CREATE",
          previous_revision_id = None
        )
      )
    } yield assocId).recover {
      case NonFatal(e) =>
        logger.error(s"Error creating variant association for haplogroupId: $haplogroupId, variantId: $variantId. Error: ${e.getMessage}")
        0
    }
  }
}
