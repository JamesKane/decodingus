package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.haplogroups.MergeStatistics
import models.domain.haplogroups.*
import play.api.Logging
import play.api.libs.json.Json
import repositories.TreeVersioningRepository

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
}

@Singleton
class TreeVersioningServiceImpl @Inject()(
  repository: TreeVersioningRepository
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
          repository.getChangeSet(id).map(_.getOrElse(
            throw new IllegalStateException(s"Failed to retrieve created change set with id $id")
          ))
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
          // Apply all pending changes
          appliedCount <- repository.applyAllPendingChanges(changeSetId)
          // Mark the change set as applied
          result <- repository.applyChangeSet(changeSetId, curatorId)
        } yield {
          if (result) {
            logger.info(s"Change set $changeSetId applied to Production by $curatorId ($appliedCount changes)")
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

  override def discardChangeSet(changeSetId: Int, curatorId: String, reason: String): Future[Boolean] = {
    repository.getChangeSet(changeSetId).flatMap {
      case Some(cs) if cs.status != ChangeSetStatus.Applied =>
        repository.discardChangeSet(changeSetId, curatorId, reason).map { result =>
          if (result) {
            logger.info(s"Change set $changeSetId discarded by $curatorId: $reason")
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

  override def reviewChange(
    changeId: Int,
    curatorId: String,
    action: ChangeStatus,
    notes: Option[String]
  ): Future[Boolean] = {
    if (action == ChangeStatus.Pending) {
      Future.failed(new IllegalArgumentException("Cannot set status back to PENDING"))
    } else {
      repository.reviewTreeChange(changeId, curatorId, notes, action).map { result =>
        if (result) {
          logger.debug(s"Change $changeId reviewed by $curatorId: $action")
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
}
