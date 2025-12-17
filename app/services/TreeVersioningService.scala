package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.MergeStatistics
import models.domain.haplogroups.*
import play.api.Logging
import play.api.libs.json.Json
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, TreeVersioningRepository, WipTreeRepository}

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for managing tree versioning (Production/WIP).
 *
 * Provides functionality to:
 * - Create and manage change sets for bulk operations
 * - Record individual tree changes during merges
 * - Finalize change sets for curator review
 * - Apply or discard change sets
 *
 * This service acts as a facade over the TreeVersioningRepository,
 * adding business logic and coordination with other services.
 */
trait TreeVersioningService {

  // ============================================================================
  // Change Set Lifecycle
  // ============================================================================

  /**
   * Create a new change set for bulk operations.
   * Only one active (DRAFT/READY_FOR_REVIEW/UNDER_REVIEW) change set per type at a time.
   */
  def createChangeSet(
    haplogroupType: HaplogroupType,
    sourceName: String,
    description: Option[String] = None,
    createdBy: String = "system"
  ): Future[ChangeSet]

  /**
   * Get the active change set for a haplogroup type (if any).
   */
  def getActiveChangeSet(haplogroupType: HaplogroupType): Future[Option[ChangeSet]]

  /**
   * Get a change set by ID with full details.
   */
  def getChangeSetDetails(id: Int): Future[Option[ChangeSetDetails]]

  /**
   * List change sets with optional filters.
   */
  def listChangeSets(
    haplogroupType: Option[HaplogroupType] = None,
    status: Option[ChangeSetStatus] = None,
    page: Int = 1,
    pageSize: Int = 20
  ): Future[(Seq[ChangeSetSummary], Int)]

  /**
   * Finalize a change set, moving it from DRAFT to READY_FOR_REVIEW.
   * Called after a merge operation completes.
   */
  def finalizeChangeSet(
    changeSetId: Int,
    statistics: MergeStatistics,
    ambiguityReportPath: Option[String] = None
  ): Future[Boolean]

  /**
   * Mark a change set as under review.
   */
  def startReview(changeSetId: Int, curatorId: String): Future[Boolean]

  /**
   * Apply a change set to Production.
   * All pending changes are applied and the set moves to APPLIED status.
   */
  def applyChangeSet(changeSetId: Int, curatorId: String): Future[Boolean]

  /**
   * Discard a change set.
   * All changes are abandoned and the set moves to DISCARDED status.
   */
  def discardChangeSet(changeSetId: Int, curatorId: String, reason: String): Future[Boolean]

  // ============================================================================
  // Change Recording
  // ============================================================================

  /**
   * Record a CREATE change (new haplogroup).
   */
  def recordCreate(
    changeSetId: Int,
    haplogroupData: String, // JSON representation of haplogroup
    parentId: Option[Int],
    ambiguityType: Option[String] = None,
    ambiguityConfidence: Option[Double] = None
  ): Future[Int]

  /**
   * Record an UPDATE change (haplogroup metadata update).
   */
  def recordUpdate(
    changeSetId: Int,
    haplogroupId: Int,
    oldData: String,   // JSON of previous state
    newData: String,   // JSON of new state
    ambiguityType: Option[String] = None,
    ambiguityConfidence: Option[Double] = None
  ): Future[Int]

  /**
   * Record a REPARENT change.
   */
  def recordReparent(
    changeSetId: Int,
    haplogroupId: Int,
    oldParentId: Option[Int],
    newParentId: Int,
    ambiguityType: Option[String] = None,
    ambiguityConfidence: Option[Double] = None
  ): Future[Int]

  /**
   * Record an ADD_VARIANT change.
   */
  def recordAddVariant(
    changeSetId: Int,
    haplogroupId: Int,
    variantId: Int
  ): Future[Int]

  /**
   * Record a REMOVE_VARIANT change.
   */
  def recordRemoveVariant(
    changeSetId: Int,
    haplogroupId: Int,
    variantId: Int
  ): Future[Int]

  // ============================================================================
  // Change Review
  // ============================================================================

  /**
   * Get pending changes for review, ordered by ambiguity confidence (lowest first).
   */
  def getPendingReviewChanges(changeSetId: Int, limit: Int = 50): Future[Seq[TreeChange]]

  /**
   * Get pending changes for review with names resolved for UI display.
   */
  def getPendingReviewChangesWithNames(changeSetId: Int, limit: Int = 50): Future[Seq[TreeChangeView]]

  /**
   * Review a specific change.
   */
  def reviewChange(
    changeId: Int,
    curatorId: String,
    action: ChangeStatus, // APPLIED, SKIPPED, REVERTED
    notes: Option[String] = None
  ): Future[Boolean]

  /**
   * Bulk approve all remaining pending changes.
   */
  def approveAllPending(changeSetId: Int, curatorId: String): Future[Int]

  // ============================================================================
  // Comments
  // ============================================================================

  /**
   * Add a comment to a change set.
   */
  def addComment(
    changeSetId: Int,
    author: String,
    content: String,
    treeChangeId: Option[Int] = None
  ): Future[Int]

  /**
   * List comments for a change set.
   */
  def listComments(changeSetId: Int): Future[Seq[ChangeSetComment]]

  // ============================================================================
  // Tree Diff (Phase 3)
  // ============================================================================

  /**
   * Get the diff between Production and WIP for a specific change set.
   * Computes differences by analyzing pending changes.
   */
  def getTreeDiff(changeSetId: Int): Future[TreeDiff]

  /**
   * Get the diff between Production and the active WIP change set (if any).
   */
  def getActiveTreeDiff(haplogroupType: HaplogroupType): Future[Option[TreeDiff]]

  /**
   * Get all changes for a change set grouped by type for diff display.
   */
  def getChangesForDiff(changeSetId: Int): Future[Seq[TreeChange]]
}

@Singleton
class TreeVersioningServiceImpl @Inject()(
  repository: TreeVersioningRepository,
  wipTreeRepository: WipTreeRepository,
  haplogroupRepository: HaplogroupCoreRepository,
  haplogroupVariantRepository: HaplogroupVariantRepository,
  auditService: CuratorAuditService
)(implicit ec: ExecutionContext)
  extends TreeVersioningService
    with Logging {

  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

  // ============================================================================
  // Change Set Lifecycle
  // ============================================================================

  override def createChangeSet(
    haplogroupType: HaplogroupType,
    sourceName: String,
    description: Option[String],
    createdBy: String
  ): Future[ChangeSet] = {
    // Check for existing active change set
    repository.getActiveChangeSet(haplogroupType).flatMap {
      case Some(existing) =>
        Future.failed(new IllegalStateException(
          s"Active change set already exists for $haplogroupType: ${existing.name} (${existing.status})"
        ))
      case None =>
        val now = LocalDateTime.now()
        val name = s"$sourceName-${now.format(timestampFormatter)}"
        val changeSet = ChangeSet(
          id = None,
          haplogroupType = haplogroupType,
          name = name,
          description = description,
          sourceName = sourceName,
          createdAt = now,
          createdBy = createdBy
        )
        repository.createChangeSet(changeSet).flatMap { id =>
          repository.getChangeSet(id).map { csOpt =>
            val cs = csOpt.getOrElse(
              throw new IllegalStateException(s"Failed to retrieve created change set with id $id")
            )
            // Log audit entry for change set creation
            auditService.logChangeSetCreate(createdBy, cs, Some(s"Created from $sourceName"))
            cs
          }
        }
    }
  }

  override def getActiveChangeSet(haplogroupType: HaplogroupType): Future[Option[ChangeSet]] = {
    repository.getActiveChangeSet(haplogroupType)
  }

  override def getChangeSetDetails(id: Int): Future[Option[ChangeSetDetails]] = {
    for {
      changeSetOpt <- repository.getChangeSet(id)
      result <- changeSetOpt match {
        case Some(changeSet) =>
          for {
            totalChanges <- repository.countTreeChanges(id)
            byType <- repository.getChangeSummaryByType(id)
            byStatus <- repository.getChangeSummaryByStatus(id)
            comments <- repository.listComments(id)
          } yield Some(ChangeSetDetails(
            changeSet = changeSet,
            totalChanges = totalChanges,
            changesByType = byType.map { case (k, v) => TreeChangeType.toDbString(k) -> v },
            changesByStatus = byStatus.map { case (k, v) => ChangeStatus.toDbString(k) -> v },
            comments = comments.toList
          ))
        case None =>
          Future.successful(None)
      }
    } yield result
  }

  override def listChangeSets(
    haplogroupType: Option[HaplogroupType],
    status: Option[ChangeSetStatus],
    page: Int,
    pageSize: Int
  ): Future[(Seq[ChangeSetSummary], Int)] = {
    val offset = (page - 1) * pageSize
    for {
      changeSets <- repository.listChangeSets(haplogroupType, status, pageSize, offset)
      total <- repository.countChangeSets(haplogroupType, status)
      summaries <- Future.sequence(changeSets.map { cs =>
        for {
          totalChanges <- repository.countTreeChanges(cs.id.get)
          pendingChanges <- repository.countTreeChanges(cs.id.get, status = Some(ChangeStatus.Pending))
          // Count reviewed as anything that's not PENDING
          reviewedChanges = totalChanges - pendingChanges
        } yield ChangeSetSummary(
          id = cs.id.get,
          haplogroupType = cs.haplogroupType,
          name = cs.name,
          sourceName = cs.sourceName,
          status = cs.status,
          createdAt = cs.createdAt,
          createdBy = cs.createdBy,
          statistics = cs.statistics,
          totalChanges = totalChanges,
          pendingChanges = pendingChanges,
          reviewedChanges = reviewedChanges
        )
      })
    } yield (summaries, total)
  }

  override def finalizeChangeSet(
    changeSetId: Int,
    statistics: MergeStatistics,
    ambiguityReportPath: Option[String]
  ): Future[Boolean] = {
    val csStats = ChangeSetStatistics(
      nodesProcessed = statistics.nodesProcessed,
      nodesCreated = statistics.nodesCreated,
      nodesUpdated = statistics.nodesUpdated,
      nodesUnchanged = statistics.nodesUnchanged,
      variantsAdded = statistics.variantsAdded,
      relationshipsCreated = statistics.relationshipsCreated,
      relationshipsUpdated = statistics.relationshipsUpdated,
      splitOperations = statistics.splitOperations,
      ambiguityCount = 0 // Will be updated from the changes
    )
    repository.finalizeChangeSet(changeSetId, csStats, ambiguityReportPath).map { result =>
      if (result) {
        logger.info(s"Change set $changeSetId finalized and ready for review")
      }
      result
    }
  }

  override def startReview(changeSetId: Int, curatorId: String): Future[Boolean] = {
    repository.getChangeSet(changeSetId).flatMap {
      case Some(cs) if cs.status == ChangeSetStatus.ReadyForReview =>
        repository.updateChangeSetStatus(changeSetId, ChangeSetStatus.UnderReview).map { result =>
          if (result) {
            logger.info(s"Change set $changeSetId now under review by $curatorId")
            // Log audit entry for status change
            auditService.logChangeSetStatusChange(
              curatorId, changeSetId,
              ChangeSetStatus.ReadyForReview, ChangeSetStatus.UnderReview,
              Some(s"Review started by $curatorId")
            )
          }
          result
        }
      case Some(cs) =>
        Future.failed(new IllegalStateException(
          s"Cannot start review: change set is ${cs.status}, expected READY_FOR_REVIEW"
        ))
      case None =>
        Future.failed(new NoSuchElementException(s"Change set $changeSetId not found"))
    }
  }

  override def applyChangeSet(changeSetId: Int, curatorId: String): Future[Boolean] = {
    repository.getChangeSet(changeSetId).flatMap {
      case Some(cs) if cs.status == ChangeSetStatus.UnderReview || cs.status == ChangeSetStatus.ReadyForReview =>
        for {
          // Check for WIP data (staging mode merge)
          wipStats <- wipTreeRepository.getWipStatistics(changeSetId)
          hasWipData = wipStats.haplogroups > 0 || wipStats.relationships > 0 ||
                       wipStats.variants > 0 || wipStats.reparents > 0

          // Apply WIP data to production if present
          appliedWipCount <- if (hasWipData) {
            logger.info(s"Applying WIP data for change set $changeSetId: " +
              s"${wipStats.haplogroups} haplogroups, ${wipStats.relationships} relationships, " +
              s"${wipStats.variants} variants, ${wipStats.reparents} reparents")
            applyWipToProduction(changeSetId, cs.haplogroupType, cs.sourceName)
          } else {
            Future.successful(0)
          }

          // Apply all pending tree changes (legacy mode)
          appliedCount <- repository.applyAllPendingChanges(changeSetId)

          // Mark the change set as applied
          result <- repository.applyChangeSet(changeSetId, curatorId)

          // Clean up WIP data (tables have ON DELETE CASCADE, but explicit cleanup is cleaner)
          _ <- if (hasWipData) wipTreeRepository.deleteWipDataForChangeSet(changeSetId)
               else Future.successful(0)

          // Get updated change set for audit
          updatedCs <- repository.getChangeSet(changeSetId)
        } yield {
          if (result) {
            val totalApplied = appliedCount + appliedWipCount
            logger.info(s"Change set $changeSetId applied to Production by $curatorId " +
              s"($totalApplied changes: $appliedCount legacy, $appliedWipCount from WIP)")
            // Log audit entry for apply action
            updatedCs.foreach { ucs =>
              auditService.logChangeSetApply(curatorId, ucs, totalApplied, Some("Applied to Production"))
            }
          }
          result
        }
      case Some(cs) =>
        Future.failed(new IllegalStateException(
          s"Cannot apply: change set is ${cs.status}, expected READY_FOR_REVIEW or UNDER_REVIEW"
        ))
      case None =>
        Future.failed(new NoSuchElementException(s"Change set $changeSetId not found"))
    }
  }

  /**
   * Apply WIP (staging) data to production tables.
   *
   * This method is called when applying a change set that was created in staging mode.
   * It copies data from WIP shadow tables to production tables, resolving placeholder
   * IDs to real production IDs.
   *
   * @param changeSetId The change set ID
   * @param haplogroupType Y or MT DNA type
   * @param sourceName Source name for new records
   * @return Number of operations applied
   */
  private def applyWipToProduction(
    changeSetId: Int,
    haplogroupType: HaplogroupType,
    sourceName: String
  ): Future[Int] = {
    val now = LocalDateTime.now()

    for {
      // 1. Get all WIP haplogroups
      wipHaplogroups <- wipTreeRepository.getWipHaplogroupsForChangeSet(changeSetId)
      _ = logger.info(s"Creating ${wipHaplogroups.size} haplogroups from WIP")

      // 2. Create production haplogroups and build placeholder → real ID mapping
      placeholderToRealId <- createProductionHaplogroups(wipHaplogroups, haplogroupType, sourceName, now)
      _ = logger.info(s"Created ${placeholderToRealId.size} production haplogroups")

      // 3. Get all WIP relationships and create them in production
      wipRelationships <- wipTreeRepository.getWipRelationshipsForChangeSet(changeSetId)
      _ = logger.info(s"Creating ${wipRelationships.size} relationships from WIP")
      relationshipsCreated <- createProductionRelationships(wipRelationships, placeholderToRealId, sourceName)

      // 4. Get all WIP variant associations and create them in production
      wipVariants <- wipTreeRepository.getWipVariantsForChangeSet(changeSetId)
      _ = logger.info(s"Creating ${wipVariants.size} variant associations from WIP")
      variantsCreated <- createProductionVariants(wipVariants, placeholderToRealId)

      // 5. Get all WIP reparents and apply them
      wipReparents <- wipTreeRepository.getWipReparentsForChangeSet(changeSetId)
      _ = logger.info(s"Applying ${wipReparents.size} reparents from WIP")
      reparentsApplied <- applyProductionReparents(wipReparents, placeholderToRealId, sourceName)

    } yield wipHaplogroups.size + relationshipsCreated + variantsCreated + reparentsApplied
  }

  /**
   * Create production haplogroups from WIP data.
   * Returns a map of placeholder ID → real production ID.
   */
  private def createProductionHaplogroups(
    wipHaplogroups: Seq[models.dal.domain.haplogroups.WipHaplogroupRow],
    haplogroupType: HaplogroupType,
    sourceName: String,
    now: LocalDateTime
  ): Future[Map[Int, Int]] = {
    // Process in order (by placeholder ID) to ensure parents are created before children
    val sortedWip = wipHaplogroups.sortBy(_.placeholderId)(Ordering[Int].reverse) // Most negative (first created) first

    sortedWip.foldLeft(Future.successful(Map.empty[Int, Int])) { (accFuture, wip) =>
      accFuture.flatMap { mapping =>
        val haplogroup = Haplogroup(
          id = None,
          name = wip.name,
          lineage = wip.lineage,
          description = wip.description,
          haplogroupType = haplogroupType,
          revisionId = 1,
          source = wip.source,
          confidenceLevel = wip.confidenceLevel,
          validFrom = now,
          validUntil = None,
          formedYbp = wip.formedYbp,
          formedYbpLower = wip.formedYbpLower,
          formedYbpUpper = wip.formedYbpUpper,
          tmrcaYbp = wip.tmrcaYbp,
          tmrcaYbpLower = wip.tmrcaYbpLower,
          tmrcaYbpUpper = wip.tmrcaYbpUpper,
          ageEstimateSource = wip.ageEstimateSource,
          provenance = wip.provenance
        )

        // Create without parent (relationships handled separately)
        haplogroupRepository.createWithParent(haplogroup, None, sourceName).map { case (realId, _) =>
          logger.debug(s"Created haplogroup ${wip.name}: placeholder ${wip.placeholderId} → real $realId")
          mapping + (wip.placeholderId -> realId)
        }
      }
    }
  }

  /**
   * Create production relationships from WIP data.
   */
  private def createProductionRelationships(
    wipRelationships: Seq[models.dal.domain.haplogroups.WipRelationshipRow],
    placeholderToRealId: Map[Int, Int],
    sourceName: String
  ): Future[Int] = {
    def resolveId(haplogroupId: Option[Int], placeholderId: Option[Int]): Option[Int] = {
      haplogroupId.orElse(placeholderId.flatMap(placeholderToRealId.get))
    }

    Future.sequence(wipRelationships.map { wip =>
      val childId = resolveId(wip.childHaplogroupId, wip.childPlaceholderId)
      val parentId = resolveId(wip.parentHaplogroupId, wip.parentPlaceholderId)

      (childId, parentId) match {
        case (Some(cid), Some(pid)) =>
          haplogroupRepository.updateParent(cid, pid, sourceName).map(_ => 1)
        case _ =>
          logger.warn(s"Could not resolve relationship: child=${wip.childHaplogroupId}/${wip.childPlaceholderId}, " +
            s"parent=${wip.parentHaplogroupId}/${wip.parentPlaceholderId}")
          Future.successful(0)
      }
    }).map(_.sum)
  }

  /**
   * Create production variant associations from WIP data.
   */
  private def createProductionVariants(
    wipVariants: Seq[models.dal.domain.haplogroups.WipHaplogroupVariantRow],
    placeholderToRealId: Map[Int, Int]
  ): Future[Int] = {
    val resolvedVariants = wipVariants.flatMap { wip =>
      val haplogroupId = wip.haplogroupId.orElse(wip.haplogroupPlaceholderId.flatMap(placeholderToRealId.get))
      haplogroupId.map(hid => (hid, wip.variantId))
    }

    if (resolvedVariants.nonEmpty) {
      haplogroupVariantRepository.bulkAddVariantsToHaplogroups(resolvedVariants).map(_.size)
    } else {
      Future.successful(0)
    }
  }

  /**
   * Apply production reparents from WIP data.
   */
  private def applyProductionReparents(
    wipReparents: Seq[models.dal.domain.haplogroups.WipReparentRow],
    placeholderToRealId: Map[Int, Int],
    sourceName: String
  ): Future[Int] = {
    Future.sequence(wipReparents.map { wip =>
      val newParentId = wip.newParentId.orElse(wip.newParentPlaceholderId.flatMap(placeholderToRealId.get))

      newParentId match {
        case Some(pid) =>
          haplogroupRepository.updateParent(wip.haplogroupId, pid, sourceName).map(_ => 1)
        case None =>
          logger.warn(s"Could not resolve reparent for haplogroup ${wip.haplogroupId}: " +
            s"newParent=${wip.newParentId}/${wip.newParentPlaceholderId}")
          Future.successful(0)
      }
    }).map(_.sum)
  }

  override def discardChangeSet(changeSetId: Int, curatorId: String, reason: String): Future[Boolean] = {
    repository.getChangeSet(changeSetId).flatMap {
      case Some(cs) if cs.status != ChangeSetStatus.Applied =>
        repository.discardChangeSet(changeSetId, curatorId, reason).map { result =>
          if (result) {
            logger.info(s"Change set $changeSetId discarded by $curatorId: $reason")
            // Log audit entry for discard action
            auditService.logChangeSetDiscard(curatorId, cs, reason)
          }
          result
        }
      case Some(cs) =>
        Future.failed(new IllegalStateException(
          s"Cannot discard: change set is already APPLIED"
        ))
      case None =>
        Future.failed(new NoSuchElementException(s"Change set $changeSetId not found"))
    }
  }

  // ============================================================================
  // Change Recording
  // ============================================================================

  private def createChange(
    changeSetId: Int,
    changeType: TreeChangeType,
    haplogroupId: Option[Int] = None,
    variantId: Option[Int] = None,
    oldParentId: Option[Int] = None,
    newParentId: Option[Int] = None,
    haplogroupData: Option[String] = None,
    oldData: Option[String] = None,
    ambiguityType: Option[String] = None,
    ambiguityConfidence: Option[Double] = None
  ): Future[Int] = {
    for {
      seqNum <- repository.getNextSequenceNum(changeSetId)
      change = TreeChange(
        id = None,
        changeSetId = changeSetId,
        changeType = changeType,
        haplogroupId = haplogroupId,
        variantId = variantId,
        oldParentId = oldParentId,
        newParentId = newParentId,
        haplogroupData = haplogroupData,
        oldData = oldData,
        sequenceNum = seqNum,
        ambiguityType = ambiguityType,
        ambiguityConfidence = ambiguityConfidence
      )
      id <- repository.createTreeChange(change)
    } yield id
  }

  override def recordCreate(
    changeSetId: Int,
    haplogroupData: String,
    parentId: Option[Int],
    ambiguityType: Option[String],
    ambiguityConfidence: Option[Double]
  ): Future[Int] = {
    createChange(
      changeSetId = changeSetId,
      changeType = TreeChangeType.Create,
      newParentId = parentId,
      haplogroupData = Some(haplogroupData),
      ambiguityType = ambiguityType,
      ambiguityConfidence = ambiguityConfidence
    )
  }

  override def recordUpdate(
    changeSetId: Int,
    haplogroupId: Int,
    oldData: String,
    newData: String,
    ambiguityType: Option[String],
    ambiguityConfidence: Option[Double]
  ): Future[Int] = {
    createChange(
      changeSetId = changeSetId,
      changeType = TreeChangeType.Update,
      haplogroupId = Some(haplogroupId),
      haplogroupData = Some(newData),
      oldData = Some(oldData),
      ambiguityType = ambiguityType,
      ambiguityConfidence = ambiguityConfidence
    )
  }

  override def recordReparent(
    changeSetId: Int,
    haplogroupId: Int,
    oldParentId: Option[Int],
    newParentId: Int,
    ambiguityType: Option[String],
    ambiguityConfidence: Option[Double]
  ): Future[Int] = {
    createChange(
      changeSetId = changeSetId,
      changeType = TreeChangeType.Reparent,
      haplogroupId = Some(haplogroupId),
      oldParentId = oldParentId,
      newParentId = Some(newParentId),
      ambiguityType = ambiguityType,
      ambiguityConfidence = ambiguityConfidence
    )
  }

  override def recordAddVariant(
    changeSetId: Int,
    haplogroupId: Int,
    variantId: Int
  ): Future[Int] = {
    createChange(
      changeSetId = changeSetId,
      changeType = TreeChangeType.AddVariant,
      haplogroupId = Some(haplogroupId),
      variantId = Some(variantId)
    )
  }

  override def recordRemoveVariant(
    changeSetId: Int,
    haplogroupId: Int,
    variantId: Int
  ): Future[Int] = {
    createChange(
      changeSetId = changeSetId,
      changeType = TreeChangeType.RemoveVariant,
      haplogroupId = Some(haplogroupId),
      variantId = Some(variantId)
    )
  }

  // ============================================================================
  // Change Review
  // ============================================================================

  override def getPendingReviewChanges(changeSetId: Int, limit: Int): Future[Seq[TreeChange]] = {
    repository.getPendingReviewChanges(changeSetId, limit)
  }

  override def getPendingReviewChangesWithNames(changeSetId: Int, limit: Int): Future[Seq[TreeChangeView]] = {
    for {
      changeSetOpt <- repository.getChangeSet(changeSetId)
      changes <- repository.getPendingReviewChanges(changeSetId, limit)
      // Collect all haplogroup IDs we need to look up
      haplogroupIds = changes.flatMap(c => c.haplogroupId.toSeq ++ c.oldParentId.toSeq ++ c.newParentId.toSeq).toSet
      names <- repository.getHaplogroupNamesById(haplogroupIds)
    } yield {
      val changeSetName = changeSetOpt.map(_.name).getOrElse(s"ChangeSet #$changeSetId")
      val sourceName = changeSetOpt.map(_.sourceName).getOrElse("Unknown")

      changes.map { change =>
        // For CREATE, try to extract name from haplogroupData JSON
        val haplogroupName = change.haplogroupId.flatMap(names.get).orElse {
          change.haplogroupData.flatMap { data =>
            (Json.parse(data) \ "name").asOpt[String]
          }
        }

        TreeChangeView(
          change = change,
          changeSetName = changeSetName,
          sourceName = sourceName,
          haplogroupName = haplogroupName,
          parentName = change.newParentId.flatMap(names.get).orElse(change.oldParentId.flatMap(names.get)),
          variantName = None // Could be enhanced later if needed
        )
      }
    }
  }

  override def reviewChange(
    changeId: Int,
    curatorId: String,
    action: ChangeStatus,
    notes: Option[String]
  ): Future[Boolean] = {
    if (action == ChangeStatus.Pending) {
      Future.failed(new IllegalArgumentException("Cannot set status back to PENDING"))
    } else {
      for {
        changeOpt <- repository.getTreeChange(changeId)
        result <- repository.reviewTreeChange(changeId, curatorId, notes, action)
      } yield {
        if (result) {
          logger.debug(s"Change $changeId reviewed by $curatorId: $action")
          // Log audit entry for change review
          changeOpt.foreach { change =>
            auditService.logChangeReview(curatorId, change, ChangeStatus.toDbString(action), notes)
          }
        }
        result
      }
    }
  }

  override def approveAllPending(changeSetId: Int, curatorId: String): Future[Int] = {
    repository.applyAllPendingChanges(changeSetId).map { count =>
      logger.info(s"Bulk approved $count pending changes in set $changeSetId by $curatorId")
      count
    }
  }

  // ============================================================================
  // Comments
  // ============================================================================

  override def addComment(
    changeSetId: Int,
    author: String,
    content: String,
    treeChangeId: Option[Int]
  ): Future[Int] = {
    val comment = ChangeSetComment(
      id = None,
      changeSetId = changeSetId,
      treeChangeId = treeChangeId,
      author = author,
      content = content,
      createdAt = LocalDateTime.now()
    )
    repository.addComment(comment)
  }

  override def listComments(changeSetId: Int): Future[Seq[ChangeSetComment]] = {
    repository.listComments(changeSetId)
  }

  // ============================================================================
  // Tree Diff (Phase 3)
  // ============================================================================

  override def getTreeDiff(changeSetId: Int): Future[TreeDiff] = {
    for {
      changeSetOpt <- repository.getChangeSet(changeSetId)
      changes <- repository.getChangesForChangeSet(changeSetId)
      // If no TreeChange records, try WIP tables (staging mode)
      result <- changeSetOpt match {
        case None =>
          Future.successful(TreeDiff.empty.copy(changeSetId = changeSetId))
        case Some(changeSet) if changes.isEmpty =>
          // No TreeChange records - compute from WIP tables (staging mode)
          computeTreeDiffFromWip(changeSet)
        case Some(changeSet) =>
          // Has TreeChange records - use those
          val haplogroupIds = changes.flatMap(c => c.haplogroupId.toSeq ++ c.oldParentId.toSeq ++ c.newParentId.toSeq ++ c.createdHaplogroupId.toSeq).toSet
          repository.getHaplogroupNamesById(haplogroupIds).map { names =>
            computeTreeDiff(changeSet, changes, names)
          }
      }
    } yield result
  }

  override def getActiveTreeDiff(haplogroupType: HaplogroupType): Future[Option[TreeDiff]] = {
    repository.getActiveChangeSet(haplogroupType).flatMap {
      case None => Future.successful(None)
      case Some(cs) => getTreeDiff(cs.id.get).map(Some(_))
    }
  }

  override def getChangesForDiff(changeSetId: Int): Future[Seq[TreeChange]] = {
    repository.getChangesForChangeSet(changeSetId)
  }

  /**
   * Compute tree diff from change set and its changes.
   * @param names Map of haplogroup ID -> name for display
   */
  private def computeTreeDiff(changeSet: ChangeSet, changes: Seq[TreeChange], names: Map[Int, String]): TreeDiff = {
    // Helper to get name or fallback to ID
    def getName(idOpt: Option[Int]): Option[String] = idOpt.map(id => names.getOrElse(id, s"#$id"))
    def getNameOrId(idOpt: Option[Int]): String = idOpt.map(id => names.getOrElse(id, s"#$id")).getOrElse("?")

    // Group changes by type
    val createChanges = changes.filter(_.changeType == TreeChangeType.Create)
    val updateChanges = changes.filter(_.changeType == TreeChangeType.Update)
    val deleteChanges = changes.filter(_.changeType == TreeChangeType.Delete)
    val reparentChanges = changes.filter(_.changeType == TreeChangeType.Reparent)
    val addVariantChanges = changes.filter(_.changeType == TreeChangeType.AddVariant)
    val removeVariantChanges = changes.filter(_.changeType == TreeChangeType.RemoveVariant)

    // Build diff entries
    val entries = List.newBuilder[TreeDiffEntry]

    // CREATE entries (Added nodes)
    createChanges.foreach { change =>
      val haplogroupName = change.haplogroupData
        .flatMap(data => (Json.parse(data) \ "name").asOpt[String])
        .orElse(change.createdHaplogroupId.flatMap(names.get))
        .getOrElse(s"Node ${change.createdHaplogroupId.getOrElse("?")}")

      val parentName = getName(change.newParentId)

      entries += TreeDiffEntry(
        diffType = DiffType.Added,
        haplogroupId = change.createdHaplogroupId,
        haplogroupName = haplogroupName,
        oldParentName = None,
        newParentName = parentName,
        changeDescription = s"New node created under parent ${parentName.getOrElse("root")}",
        changeIds = List(change.id.get)
      )
    }

    // DELETE entries (Removed nodes)
    deleteChanges.foreach { change =>
      entries += TreeDiffEntry(
        diffType = DiffType.Removed,
        haplogroupId = change.haplogroupId,
        haplogroupName = getNameOrId(change.haplogroupId),
        oldParentName = None,
        newParentName = None,
        changeDescription = "Node marked for deletion",
        changeIds = List(change.id.get)
      )
    }

    // REPARENT entries
    reparentChanges.foreach { change =>
      val oldParent = getName(change.oldParentId)
      val newParent = getName(change.newParentId)

      entries += TreeDiffEntry(
        diffType = DiffType.Reparented,
        haplogroupId = change.haplogroupId,
        haplogroupName = getNameOrId(change.haplogroupId),
        oldParentName = oldParent,
        newParentName = newParent,
        changeDescription = s"Parent changed from ${oldParent.getOrElse("none")} to ${newParent.getOrElse("none")}",
        changeIds = List(change.id.get)
      )
    }

    // Group UPDATE and variant changes by haplogroup for Modified entries
    val updatesByHg = updateChanges.groupBy(_.haplogroupId)
    val variantAddsByHg = addVariantChanges.groupBy(_.haplogroupId)
    val variantRemovesByHg = removeVariantChanges.groupBy(_.haplogroupId)

    val allModifiedHgs = (updatesByHg.keySet ++ variantAddsByHg.keySet ++ variantRemovesByHg.keySet).flatten

    allModifiedHgs.foreach { hgId =>
      val updates = updatesByHg.getOrElse(Some(hgId), Seq.empty)
      val variantAdds = variantAddsByHg.getOrElse(Some(hgId), Seq.empty)
      val variantRemoves = variantRemovesByHg.getOrElse(Some(hgId), Seq.empty)

      val changeIds = (updates.flatMap(_.id) ++ variantAdds.flatMap(_.id) ++ variantRemoves.flatMap(_.id)).toList
      val description = List(
        if (updates.nonEmpty) s"${updates.size} update(s)" else "",
        if (variantAdds.nonEmpty) s"${variantAdds.size} variant(s) added" else "",
        if (variantRemoves.nonEmpty) s"${variantRemoves.size} variant(s) removed" else ""
      ).filter(_.nonEmpty).mkString(", ")

      entries += TreeDiffEntry(
        diffType = DiffType.Modified,
        haplogroupId = Some(hgId),
        haplogroupName = names.getOrElse(hgId, s"#$hgId"),
        oldParentName = None,
        newParentName = None,
        changeDescription = description,
        changeIds = changeIds,
        variantsAdded = variantAdds.flatMap(_.variantId).map(v => s"#$v").toList,
        variantsRemoved = variantRemoves.flatMap(_.variantId).map(v => s"#$v").toList
      )
    }

    // Build summary
    val summary = TreeDiffSummary(
      totalChanges = changes.size,
      nodesAdded = createChanges.size,
      nodesRemoved = deleteChanges.size,
      nodesModified = allModifiedHgs.size,
      nodesReparented = reparentChanges.size,
      variantsAdded = addVariantChanges.size,
      variantsRemoved = removeVariantChanges.size
    )

    TreeDiff(
      changeSetId = changeSet.id.get,
      changeSetName = changeSet.name,
      haplogroupType = changeSet.haplogroupType,
      entries = entries.result(),
      summary = summary
    )
  }

  /**
   * Compute tree diff from WIP tables (for staging mode).
   * This is used when no TreeChange records exist because changes are staged in WIP tables.
   */
  private def computeTreeDiffFromWip(changeSet: ChangeSet): Future[TreeDiff] = {
    val changeSetId = changeSet.id.get

    for {
      // Get all WIP data
      wipHaplogroups <- wipTreeRepository.getWipHaplogroupsForChangeSet(changeSetId)
      wipReparents <- wipTreeRepository.getWipReparentsForChangeSet(changeSetId)
      wipVariants <- wipTreeRepository.getWipVariantsForChangeSet(changeSetId)
      wipRelationships <- wipTreeRepository.getWipRelationshipsForChangeSet(changeSetId)

      // Collect all production haplogroup IDs we need to look up names for
      productionHgIds = (
        wipReparents.map(_.haplogroupId) ++
        wipReparents.flatMap(_.oldParentId) ++
        wipReparents.flatMap(_.newParentId) ++
        wipVariants.flatMap(_.haplogroupId) ++
        wipRelationships.flatMap(_.childHaplogroupId) ++
        wipRelationships.flatMap(_.parentHaplogroupId)
      ).toSet

      names <- repository.getHaplogroupNamesById(productionHgIds)
    } yield {
      val entries = List.newBuilder[TreeDiffEntry]

      // Helper to get name
      def getName(idOpt: Option[Int]): Option[String] = idOpt.map(id => names.getOrElse(id, s"#$id"))

      // Build map of placeholder ID -> WIP haplogroup name
      val wipNames = wipHaplogroups.map(h => h.placeholderId -> h.name).toMap

      // Build map of placeholder ID -> parent info from relationships
      val parentByPlaceholder = wipRelationships
        .filter(_.childPlaceholderId.isDefined)
        .map(r => r.childPlaceholderId.get -> (r.parentHaplogroupId, r.parentPlaceholderId))
        .toMap

      // CREATE entries from WIP haplogroups
      wipHaplogroups.foreach { wh =>
        val parentInfo = parentByPlaceholder.get(wh.placeholderId)
        val parentName = parentInfo.flatMap {
          case (Some(prodId), _) => names.get(prodId)
          case (_, Some(placeholderId)) => wipNames.get(placeholderId)
          case _ => None
        }

        entries += TreeDiffEntry(
          diffType = DiffType.Added,
          haplogroupId = None, // Placeholder, not production ID
          haplogroupName = wh.name,
          oldParentName = None,
          newParentName = parentName,
          changeDescription = s"New node to be created under parent ${parentName.getOrElse("root")}",
          changeIds = List.empty
        )
      }

      // REPARENT entries from WIP reparents
      wipReparents.foreach { wr =>
        val haplogroupName = names.getOrElse(wr.haplogroupId, s"#${wr.haplogroupId}")
        val oldParent = getName(wr.oldParentId)
        val newParent = wr.newParentId.flatMap(names.get).orElse(
          wr.newParentPlaceholderId.flatMap(wipNames.get)
        )

        entries += TreeDiffEntry(
          diffType = DiffType.Reparented,
          haplogroupId = Some(wr.haplogroupId),
          haplogroupName = haplogroupName,
          oldParentName = oldParent,
          newParentName = newParent,
          changeDescription = s"Parent to be changed from ${oldParent.getOrElse("none")} to ${newParent.getOrElse("new node")}",
          changeIds = List.empty
        )
      }

      // MODIFIED entries from WIP variants (for existing production haplogroups)
      val variantsByProductionHg = wipVariants
        .filter(_.haplogroupId.isDefined)
        .groupBy(_.haplogroupId.get)

      variantsByProductionHg.foreach { case (hgId, variants) =>
        val haplogroupName = names.getOrElse(hgId, s"#$hgId")

        entries += TreeDiffEntry(
          diffType = DiffType.Modified,
          haplogroupId = Some(hgId),
          haplogroupName = haplogroupName,
          oldParentName = None,
          newParentName = None,
          changeDescription = s"${variants.size} variant(s) to be added",
          changeIds = List.empty,
          variantsAdded = variants.map(v => s"#${v.variantId}").toList
        )
      }

      // Build summary
      val summary = TreeDiffSummary(
        totalChanges = wipHaplogroups.size + wipReparents.size + variantsByProductionHg.size,
        nodesAdded = wipHaplogroups.size,
        nodesRemoved = 0,
        nodesModified = variantsByProductionHg.size,
        nodesReparented = wipReparents.size,
        variantsAdded = wipVariants.size,
        variantsRemoved = 0
      )

      TreeDiff(
        changeSetId = changeSetId,
        changeSetName = changeSet.name,
        haplogroupType = changeSet.haplogroupType,
        entries = entries.result(),
        summary = summary
      )
    }
  }
}
