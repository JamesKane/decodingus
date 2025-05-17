package services

import models.*
import models.api.{TreeDTO, TreeNodeDTO, VariantDTO}
import repositories.*

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

case class TreeImportConfig(
                             initialAuthor: String = "system",
                             source: String = "initial_import",
                             defaultConfidenceLevel: String = "MEDIUM",
                             backboneConfidenceLevel: String = "HIGH"
                           )


class TreeImporter(
                    haplogroupRevisionRepository: HaplogroupRevisionRepository,
                    haplogroupRelationshipRepository: HaplogroupRelationshipRepository,
                    haplogroupVariantRepository: HaplogroupVariantRepository,
                    haplogroupVariantMetadataRepository: HaplogroupVariantMetadataRepository,
                    haplogroupRevisionMetadataRepository: HaplogroupRevisionMetadataRepository,
                    genbankContigRepository: GenbankContigRepository,
                    variantRepository: VariantRepository,
                    config: TreeImportConfig
                  )(implicit ec: ExecutionContext) {

  def importTree(tree: TreeDTO, haplogroupType: HaplogroupType): Future[Unit] = {
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

  private def createHaplogroup(
                                node: TreeNodeDTO,
                                haplogroupType: HaplogroupType,
                                timestamp: LocalDateTime
                              ): Future[Int] = {
    val haplogroup = Haplogroup(
      id = None,
      name = node.name,
      lineage = None, // Could be derived from parent chain if needed
      description = None,
      haplogroupType = haplogroupType,
      revisionId = 1,
      source = config.source,
      confidenceLevel = if (node.isBackbone) config.backboneConfidenceLevel else config.defaultConfidenceLevel,
      validFrom = timestamp,
      validUntil = None
    )

    haplogroupRevisionRepository.createNewRevision(haplogroup)
  }

  private def createRelationship(
                                  parentId: Int,
                                  childId: Int,
                                  timestamp: LocalDateTime
                                ): Future[Unit] = {
    val relationship = HaplogroupRelationship(
      id = None,
      childHaplogroupId = childId,
      parentHaplogroupId = parentId,
      revisionId = 1,
      validFrom = timestamp,
      validUntil = None,
      source = config.source,
    )

    val metadata = RelationshipRevisionMetadata(
      haplogroup_relationship_id = 0, // Will be set after relationship creation
      revisionId = 1,
      author = config.initialAuthor,
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

  private def createVariants(
                              variants: Seq[VariantDTO],
                              haplogroupId: Int,
                              timestamp: LocalDateTime
                            ): Future[Unit] = {
    Future.sequence(variants.map { v =>
      for {
        // Create or get the variant
        variantId <- createOrGetVariant(v)

        // Create the haplogroup-variant association
        assocId <- haplogroupVariantRepository.addVariantToHaplogroup(haplogroupId, variantId)

        // Add metadata for the association
        _ <- haplogroupVariantMetadataRepository.addVariantRevisionMetadata(
          HaplogroupVariantMetadata(
            haplogroup_variant_id = assocId,
            revision_id = 1,
            author = config.initialAuthor,
            timestamp = timestamp,
            comment = "Initial variant import",
            change_type = "CREATE",
            previous_revision_id = None
          )
        )
      } yield ()
    }).map(_ => ())
  }

  private def createOrGetVariant(v: VariantDTO): Future[Int] = {
    val (contigAccession, coord) = v.coordinates.head

    for {
      // First find the contig using the repository
      contig <- genbankContigRepository.findByAccession(contigAccession).flatMap {
        case Some(c) => Future.successful(c)
        case None => Future.failed(new RuntimeException(s"GenBank contig not found: $contigAccession"))
      }

      // Then find or create the variant
      variantId <- variantRepository.findVariant(
        contigId = contig.id.get,
        position = coord.start,
        referenceAllele = coord.anc,
        alternateAllele = coord.der
      ).flatMap {
        case Some(variant) => Future.successful(variant.variantId.get)
        case None => variantRepository.createVariant(
          Variant(
            variantId = None,
            genbankContigId = contig.id.get,
            position = coord.start,
            referenceAllele = coord.anc,
            alternateAllele = coord.der,
            variantType = v.variantType,
            rsId = Some(v.name).filter(_.startsWith("rs")),
            commonName = Some(v.name).filterNot(_.startsWith("rs"))
          )
        )
      }
    } yield variantId
  }
}