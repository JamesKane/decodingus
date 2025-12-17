package services

import jakarta.inject.{Inject, Singleton}
import models.dal.domain.haplogroups.{WipHaplogroupRow, WipHaplogroupVariantRow, WipRelationshipRow, WipReparentRow}
import models.domain.haplogroups.{Haplogroup, MergeContext}
import play.api.Logging
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, WipTreeRepository}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

/**
 * Helper service for routing tree merge operations to either WIP (staging) tables
 * or production tables based on the merge context's staging mode flag.
 *
 * This service manages:
 * - Placeholder ID generation for WIP nodes (negative IDs to avoid collision)
 * - Routing haplogroup creation to WIP or production tables
 * - Routing variant additions to WIP or production tables
 * - Routing reparent operations to WIP or production tables
 *
 * In staging mode, all changes are written to WIP shadow tables for curator review.
 * When not in staging mode, changes are applied directly to production tables.
 */
@Singleton
class TreeMergeStagingHelper @Inject()(
  haplogroupRepository: HaplogroupCoreRepository,
  haplogroupVariantRepository: HaplogroupVariantRepository,
  wipTreeRepository: WipTreeRepository
)(implicit ec: ExecutionContext) extends Logging {

  // Placeholder ID counter for WIP nodes (starts negative to avoid collision with real IDs)
  // Reset per merge operation via resetPlaceholderCounter()
  private val placeholderCounter = new AtomicInteger(-1)

  /**
   * Reset the placeholder counter for a new merge operation.
   * Should be called at the start of each merge to ensure clean placeholder IDs.
   */
  def resetPlaceholderCounter(): Unit = {
    placeholderCounter.set(-1)
  }

  /**
   * Check if an ID is a placeholder (negative = WIP node, positive = production node).
   */
  def isPlaceholder(id: Int): Boolean = id < 0

  /**
   * Create a haplogroup - routes to WIP table if staging mode, else production.
   *
   * @param haplogroup The haplogroup to create
   * @param parentId Parent ID (can be production ID or placeholder)
   * @param context Merge context with staging mode flag
   * @return (newId, relationshipId) - newId is placeholder in staging mode, real ID otherwise
   */
  def createHaplogroupStaged(
    haplogroup: Haplogroup,
    parentId: Option[Int],
    context: MergeContext
  ): Future[(Int, Option[Int])] = {
    if (context.stagingMode) {
      val changeSetId = context.changeSetId.getOrElse(
        throw new IllegalStateException("Staging mode requires a change set ID")
      )
      val placeholderId = placeholderCounter.getAndDecrement()

      // Create WIP haplogroup row
      val wipHaplogroup = WipHaplogroupRow(
        id = None,
        changeSetId = changeSetId,
        placeholderId = placeholderId,
        name = haplogroup.name,
        lineage = haplogroup.lineage,
        description = haplogroup.description,
        haplogroupType = haplogroup.haplogroupType,
        source = haplogroup.source,
        confidenceLevel = haplogroup.confidenceLevel,
        formedYbp = haplogroup.formedYbp,
        formedYbpLower = haplogroup.formedYbpLower,
        formedYbpUpper = haplogroup.formedYbpUpper,
        tmrcaYbp = haplogroup.tmrcaYbp,
        tmrcaYbpLower = haplogroup.tmrcaYbpLower,
        tmrcaYbpUpper = haplogroup.tmrcaYbpUpper,
        ageEstimateSource = haplogroup.ageEstimateSource,
        provenance = haplogroup.provenance,
        createdAt = context.timestamp
      )

      for {
        _ <- wipTreeRepository.createWipHaplogroup(wipHaplogroup)

        // Create WIP relationship if parent specified
        _ <- parentId match {
          case Some(pid) =>
            val wipRelationship = WipRelationshipRow(
              id = None,
              changeSetId = changeSetId,
              childHaplogroupId = None, // Child is WIP
              childPlaceholderId = Some(placeholderId),
              parentHaplogroupId = if (isPlaceholder(pid)) None else Some(pid),
              parentPlaceholderId = if (isPlaceholder(pid)) Some(pid) else None,
              source = context.sourceName,
              createdAt = context.timestamp
            )
            wipTreeRepository.createWipRelationship(wipRelationship)
          case None =>
            Future.successful(0)
        }
      } yield (placeholderId, None) // Return placeholder ID
    } else {
      haplogroupRepository.createWithParent(haplogroup, parentId, context.sourceName)
    }
  }

  /**
   * Add variants to a haplogroup - routes to WIP table if staging mode.
   *
   * @param haplogroupId Haplogroup ID (can be production ID or placeholder)
   * @param variantIds Variant IDs to add (always production variant IDs)
   * @param context Merge context
   * @return IDs of created associations
   */
  def addVariantsStaged(
    haplogroupId: Int,
    variantIds: Seq[Int],
    context: MergeContext
  ): Future[Seq[Int]] = {
    if (variantIds.isEmpty) {
      Future.successful(Seq.empty)
    } else if (context.stagingMode) {
      val changeSetId = context.changeSetId.getOrElse(
        throw new IllegalStateException("Staging mode requires a change set ID")
      )
      val rows = variantIds.map { vid =>
        WipHaplogroupVariantRow(
          id = None,
          changeSetId = changeSetId,
          haplogroupId = if (isPlaceholder(haplogroupId)) None else Some(haplogroupId),
          haplogroupPlaceholderId = if (isPlaceholder(haplogroupId)) Some(haplogroupId) else None,
          variantId = vid,
          source = Some(context.sourceName),
          createdAt = context.timestamp
        )
      }
      // Use upsert to handle cases where the same variant is added multiple times
      wipTreeRepository.upsertWipHaplogroupVariants(rows)
    } else {
      haplogroupVariantRepository.bulkAddVariantsToHaplogroups(
        variantIds.map(vid => (haplogroupId, vid))
      )
    }
  }

  /**
   * Reparent an existing production haplogroup - routes to WIP table if staging mode.
   *
   * @param haplogroupId The production haplogroup to reparent
   * @param oldParentId Current parent (for reference)
   * @param newParentId New parent ID (can be production ID or placeholder)
   * @param context Merge context
   */
  def reparentStaged(
    haplogroupId: Int,
    oldParentId: Option[Int],
    newParentId: Int,
    context: MergeContext
  ): Future[Unit] = {
    if (context.stagingMode) {
      val changeSetId = context.changeSetId.getOrElse(
        throw new IllegalStateException("Staging mode requires a change set ID")
      )
      val wipReparent = WipReparentRow(
        id = None,
        changeSetId = changeSetId,
        haplogroupId = haplogroupId, // Production haplogroup being reparented
        oldParentId = oldParentId,
        newParentId = if (isPlaceholder(newParentId)) None else Some(newParentId),
        newParentPlaceholderId = if (isPlaceholder(newParentId)) Some(newParentId) else None,
        source = context.sourceName,
        createdAt = context.timestamp
      )
      // Use upsert to handle cases where the same node is reparented multiple times
      // (e.g., once by SUBTREE_LOOK_AHEAD and again by DEPTH_GRAFT)
      wipTreeRepository.upsertWipReparent(wipReparent).map(_ => ())
    } else {
      haplogroupRepository.updateParent(haplogroupId, newParentId, context.sourceName).map(_ => ())
    }
  }
}
