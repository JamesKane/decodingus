package services

import models.*
import models.api.{TreeDTO, TreeNodeDTO, VariantDTO}
import play.api.Logging
import repositories.*

import java.time.LocalDateTime
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
 * Configuration class for tree import settings.
 *
 * This class represents the configurable parameters used during the process of
 * importing tree data. These parameters include information about the author of
 * the import, the source of the data, and confidence levels for the imported data.
 *
 * @param initialAuthor           The identifier for the author of the import. Defaults to "system".
 * @param source                  The source of the imported tree data. Defaults to "initial_import".
 * @param defaultConfidenceLevel  The confidence level to assign to non-backbone data during import. Defaults to "MEDIUM".
 * @param backboneConfidenceLevel The confidence level to assign to backbone data during import. Defaults to "HIGH".
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
 *
 * @constructor Creates a new instance of TreeImporter with the required repositories and configuration.
 * @param haplogroupRevisionRepository         Repository for managing haplogroup and revision data.
 * @param haplogroupRelationshipRepository     Repository for managing haplogroup relationships.
 * @param haplogroupVariantRepository          Repository for managing haplogroup-variant associations.
 * @param haplogroupVariantMetadataRepository  Repository for managing metadata for haplogroup-variant revisions.
 * @param haplogroupRevisionMetadataRepository Repository for managing metadata for haplogroup revisions.
 * @param genbankContigRepository              Repository for accessing GenBank contig data.
 * @param variantRepository                    Repository for managing variant data.
 * @param config                               Configuration related to tree importing, such as source and confidence levels.
 * @param ec                                   Implicit execution context for managing asynchronous computations.
 */
class TreeImporter @Inject()(
                              haplogroupRevisionRepository: HaplogroupRevisionRepository,
                              haplogroupRelationshipRepository: HaplogroupRelationshipRepository,
                              haplogroupVariantRepository: HaplogroupVariantRepository,
                              haplogroupVariantMetadataRepository: HaplogroupVariantMetadataRepository,
                              haplogroupRevisionMetadataRepository: HaplogroupRevisionMetadataRepository,
                              genbankContigRepository: GenbankContigRepository,
                              variantRepository: VariantRepository
                            )(implicit ec: ExecutionContext) extends Logging {
  private val defaultSettings = TreeImportSettings()


  /**
   * Imports a tree structure into the system by recursively processing its nodes,
   * creating haplogroups, relationships, and variants.
   *
   * @param tree           The tree structure to be imported, represented as a `TreeDTO`.
   * @param haplogroupType The type of haplogroup classification to apply (e.g., paternal or maternal lineage).
   * @return A `Future` that completes when the tree has been successfully imported, or fails in case of an error.
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
   * Creates a new haplogroup entry based on the provided tree node and its attributes.
   *
   * This method constructs a Haplogroup entity using the data from the given TreeNodeDTO,
   * including its name, backbone flag, and the specified haplogroup type. It also assigns
   * a confidence level and validity timestamps. The constructed haplogroup is then passed
   * to the repository to create a new revision, which is persisted to the database.
   *
   * @param node           The TreeNodeDTO representing the hierarchical structure and metadata for the haplogroup.
   * @param haplogroupType The type of haplogroup classification, e.g., paternal (Y) or maternal (MT).
   * @param timestamp      The timestamp indicating the creation or validity start time for the haplogroup.
   * @return A Future containing the unique integer identifier of the newly created haplogroup revision.
   */
  private def createHaplogroup(
                                node: TreeNodeDTO,
                                haplogroupType: HaplogroupType,
                                timestamp: LocalDateTime
                              )(implicit settings: TreeImportSettings): Future[Int] = {
    val haplogroup = Haplogroup(
      id = None,
      name = node.name,
      lineage = None,
      description = None,
      haplogroupType = haplogroupType,
      revisionId = 1,
      source = settings.source,
      confidenceLevel = if (node.isBackbone) settings.backboneConfidenceLevel else settings.defaultConfidenceLevel,
      validFrom = timestamp,
      validUntil = None
    )

    logger.debug(s"Creating new haplogroup revision for ${node.name}")
    haplogroupRevisionRepository.createNewRevision(haplogroup).map { id =>
      logger.debug(s"Created haplogroup with ID: $id for ${node.name}")
      id
    }
  }


  /**
   * Creates a relationship between a parent haplogroup and a child haplogroup,
   * with associated metadata for tracking revisions.
   *
   * @param parentId  The unique identifier of the parent haplogroup.
   * @param childId   The unique identifier of the child haplogroup.
   * @param timestamp The timestamp indicating when the relationship was created or became valid.
   * @return A Future containing Unit, which completes when the relationship and its metadata
   *         are successfully created, or fails with an exception if an error occurs.
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
   * Creates or retrieves genetic variants, associates them with a specific haplogroup,
   * and stores the related metadata for tracking changes.
   *
   * @param variants     A sequence of `VariantDTO` instances representing the genomic variants to be processed.
   * @param haplogroupId The unique identifier of the haplogroup to associate the variants with.
   * @param timestamp    The timestamp indicating the moment of creation or update for the variants.
   * @return A `Future` containing `Unit` that completes once all variants are processed and associated,
   *         or fails with an exception if an error occurs during execution.
   */
  private def createVariants(
                              variants: Seq[VariantDTO],
                              haplogroupId: Int,
                              timestamp: LocalDateTime
                            )(implicit settings: TreeImportSettings): Future[Unit] = {
    logger.debug(s"Starting to process ${variants.size} variants for haplogroup $haplogroupId")

    // Convert DTOs to Variant entities
    val variantEntities = variants.flatMap { v =>
      v.coordinates.map { case (contigAccession, coord) =>
        (contigAccession, Variant(
          variantId = None,
          genbankContigId = 0,
          position = coord.start,
          referenceAllele = coord.anc,
          alternateAllele = coord.der,
          variantType = v.variantType,
          rsId = Some(v.name).filter(_.startsWith("rs")),
          commonName = Some(v.name).filterNot(_.startsWith("rs"))
        ))
      }
    }

    // Group by contig accession for efficient processing
    val groupedVariants = variantEntities.groupBy(_._1)

    // Process groups sequentially to avoid overwhelming the connection pool
    groupedVariants.toSeq.foldLeft(Future.successful(())) { case (prevFuture, (contigAccession, variants)) =>
      prevFuture.flatMap { _ =>
        // Process each batch of 100 variants
        variants.grouped(100).foldLeft(Future.successful(())) { case (batchFuture, batch) =>
          batchFuture.flatMap { _ =>
            for {
              contig <- genbankContigRepository.findByAccession(contigAccession).flatMap {
                case Some(c) => Future.successful(c)
                case None => Future.failed(new RuntimeException(s"GenBank contig not found: $contigAccession"))
              }
              variantsWithContig = batch.map(_._2.copy(genbankContigId = contig.id.get))
              variantIds <- variantRepository.findOrCreateVariantsBatch(variantsWithContig)
              _ <- variantIds.foldLeft(Future.successful(())) { case (f, variantId) =>
                f.flatMap(_ => createVariantAssociation(haplogroupId, variantId, timestamp).map(_ => ()))
              }
            } yield ()
          }
        }
      }
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