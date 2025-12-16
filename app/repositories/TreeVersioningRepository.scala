package repositories

import jakarta.inject.Inject
import models.HaplogroupType
import models.dal.domain.haplogroups.{ChangeSetCommentRow, ChangeSetRow, TreeChangeRow}
import models.domain.haplogroups.*
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for Tree Versioning System.
 *
 * Manages change sets and individual tree changes for Production/WIP versioning.
 */
trait TreeVersioningRepository {

  // ============================================================================
  // Change Set Operations
  // ============================================================================

  /**
   * Create a new change set.
   */
  def createChangeSet(changeSet: ChangeSet): Future[Int]

  /**
   * Get a change set by ID.
   */
  def getChangeSet(id: Int): Future[Option[ChangeSet]]

  /**
   * Get a change set by name and type.
   */
  def getChangeSetByName(name: String, haplogroupType: HaplogroupType): Future[Option[ChangeSet]]

  /**
   * Get the active (DRAFT or READY_FOR_REVIEW) change set for a type.
   * Only one active change set per type at a time.
   */
  def getActiveChangeSet(haplogroupType: HaplogroupType): Future[Option[ChangeSet]]

  /**
   * List change sets with optional filters.
   */
  def listChangeSets(
    haplogroupType: Option[HaplogroupType] = None,
    status: Option[ChangeSetStatus] = None,
    limit: Int = 20,
    offset: Int = 0
  ): Future[Seq[ChangeSet]]

  /**
   * Count change sets matching filters.
   */
  def countChangeSets(
    haplogroupType: Option[HaplogroupType] = None,
    status: Option[ChangeSetStatus] = None
  ): Future[Int]

  /**
   * Update a change set.
   */
  def updateChangeSet(changeSet: ChangeSet): Future[Boolean]

  /**
   * Update change set status.
   */
  def updateChangeSetStatus(id: Int, status: ChangeSetStatus): Future[Boolean]

  /**
   * Finalize a change set (move from DRAFT to READY_FOR_REVIEW).
   */
  def finalizeChangeSet(
    id: Int,
    statistics: ChangeSetStatistics,
    ambiguityReportPath: Option[String]
  ): Future[Boolean]

  /**
   * Apply a change set (move to APPLIED status).
   */
  def applyChangeSet(id: Int, appliedBy: String): Future[Boolean]

  /**
   * Discard a change set (move to DISCARDED status).
   */
  def discardChangeSet(id: Int, discardedBy: String, reason: String): Future[Boolean]

  // ============================================================================
  // Tree Change Operations
  // ============================================================================

  /**
   * Record a new tree change.
   */
  def createTreeChange(change: TreeChange): Future[Int]

  /**
   * Bulk insert tree changes.
   */
  def createTreeChanges(changes: Seq[TreeChange]): Future[Seq[Int]]

  /**
   * Get a tree change by ID.
   */
  def getTreeChange(id: Int): Future[Option[TreeChange]]

  /**
   * List tree changes for a change set.
   */
  def listTreeChanges(
    changeSetId: Int,
    changeType: Option[TreeChangeType] = None,
    status: Option[ChangeStatus] = None,
    limit: Int = 100,
    offset: Int = 0
  ): Future[Seq[TreeChange]]

  /**
   * Count tree changes for a change set.
   */
  def countTreeChanges(
    changeSetId: Int,
    changeType: Option[TreeChangeType] = None,
    status: Option[ChangeStatus] = None
  ): Future[Int]

  /**
   * Get the next sequence number for a change set.
   */
  def getNextSequenceNum(changeSetId: Int): Future[Int]

  /**
   * Update a tree change.
   */
  def updateTreeChange(change: TreeChange): Future[Boolean]

  /**
   * Update tree change status.
   */
  def updateTreeChangeStatus(id: Int, status: ChangeStatus): Future[Boolean]

  /**
   * Mark a tree change as reviewed.
   */
  def reviewTreeChange(
    id: Int,
    reviewedBy: String,
    notes: Option[String],
    newStatus: ChangeStatus
  ): Future[Boolean]

  /**
   * Apply all pending changes in a change set (batch update to APPLIED).
   */
  def applyAllPendingChanges(changeSetId: Int): Future[Int]

  /**
   * Get pending changes for review (ordered by ambiguity confidence ASC).
   */
  def getPendingReviewChanges(changeSetId: Int, limit: Int = 50): Future[Seq[TreeChange]]

  /**
   * Get all changes for a change set (for diff computation).
   * Returns all changes without pagination, ordered by sequence number.
   */
  def getChangesForChangeSet(changeSetId: Int): Future[Seq[TreeChange]]

  /**
   * Get change summary by type for a change set.
   */
  def getChangeSummaryByType(changeSetId: Int): Future[Map[TreeChangeType, Int]]

  /**
   * Get change summary by status for a change set.
   */
  def getChangeSummaryByStatus(changeSetId: Int): Future[Map[ChangeStatus, Int]]

  // ============================================================================
  // Comment Operations
  // ============================================================================

  /**
   * Add a comment to a change set or specific change.
   */
  def addComment(comment: ChangeSetComment): Future[Int]

  /**
   * List comments for a change set.
   */
  def listComments(changeSetId: Int): Future[Seq[ChangeSetComment]]

  /**
   * List comments for a specific tree change.
   */
  def listCommentsForChange(treeChangeId: Int): Future[Seq[ChangeSetComment]]
}

class TreeVersioningRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with TreeVersioningRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.{changeSets, treeChanges, changeSetComments}
  import models.dal.MyPostgresProfile.api.*

  // ============================================================================
  // Conversion Helpers
  // ============================================================================

  private def toChangeSet(row: ChangeSetRow): ChangeSet = ChangeSet(
    id = row.id,
    haplogroupType = row.haplogroupType,
    name = row.name,
    description = row.description,
    sourceName = row.sourceName,
    createdAt = row.createdAt,
    createdBy = row.createdBy,
    finalizedAt = row.finalizedAt,
    appliedAt = row.appliedAt,
    appliedBy = row.appliedBy,
    discardedAt = row.discardedAt,
    discardedBy = row.discardedBy,
    discardReason = row.discardReason,
    status = ChangeSetStatus.fromString(row.status),
    statistics = ChangeSetStatistics(
      nodesProcessed = row.nodesProcessed,
      nodesCreated = row.nodesCreated,
      nodesUpdated = row.nodesUpdated,
      nodesUnchanged = row.nodesUnchanged,
      variantsAdded = row.variantsAdded,
      relationshipsCreated = row.relationshipsCreated,
      relationshipsUpdated = row.relationshipsUpdated,
      splitOperations = row.splitOperations,
      ambiguityCount = row.ambiguityCount
    ),
    ambiguityReportPath = row.ambiguityReportPath
  )

  private def toChangeSetRow(cs: ChangeSet): ChangeSetRow = ChangeSetRow(
    id = cs.id,
    haplogroupType = cs.haplogroupType,
    name = cs.name,
    description = cs.description,
    sourceName = cs.sourceName,
    createdAt = cs.createdAt,
    createdBy = cs.createdBy,
    finalizedAt = cs.finalizedAt,
    appliedAt = cs.appliedAt,
    appliedBy = cs.appliedBy,
    discardedAt = cs.discardedAt,
    discardedBy = cs.discardedBy,
    discardReason = cs.discardReason,
    status = ChangeSetStatus.toDbString(cs.status),
    nodesProcessed = cs.statistics.nodesProcessed,
    nodesCreated = cs.statistics.nodesCreated,
    nodesUpdated = cs.statistics.nodesUpdated,
    nodesUnchanged = cs.statistics.nodesUnchanged,
    variantsAdded = cs.statistics.variantsAdded,
    relationshipsCreated = cs.statistics.relationshipsCreated,
    relationshipsUpdated = cs.statistics.relationshipsUpdated,
    splitOperations = cs.statistics.splitOperations,
    ambiguityCount = cs.statistics.ambiguityCount,
    ambiguityReportPath = cs.ambiguityReportPath,
    metadata = None
  )

  private def toTreeChange(row: TreeChangeRow): TreeChange = TreeChange(
    id = row.id,
    changeSetId = row.changeSetId,
    changeType = TreeChangeType.fromString(row.changeType),
    haplogroupId = row.haplogroupId,
    variantId = row.variantId,
    oldParentId = row.oldParentId,
    newParentId = row.newParentId,
    haplogroupData = row.haplogroupData,
    oldData = row.oldData,
    createdHaplogroupId = row.createdHaplogroupId,
    sequenceNum = row.sequenceNum,
    status = ChangeStatus.fromString(row.status),
    reviewedAt = row.reviewedAt,
    reviewedBy = row.reviewedBy,
    reviewNotes = row.reviewNotes,
    createdAt = row.createdAt,
    appliedAt = row.appliedAt,
    ambiguityType = row.ambiguityType,
    ambiguityConfidence = row.ambiguityConfidence
  )

  private def toTreeChangeRow(tc: TreeChange): TreeChangeRow = TreeChangeRow(
    id = tc.id,
    changeSetId = tc.changeSetId,
    changeType = TreeChangeType.toDbString(tc.changeType),
    haplogroupId = tc.haplogroupId,
    variantId = tc.variantId,
    oldParentId = tc.oldParentId,
    newParentId = tc.newParentId,
    haplogroupData = tc.haplogroupData,
    oldData = tc.oldData,
    createdHaplogroupId = tc.createdHaplogroupId,
    sequenceNum = tc.sequenceNum,
    status = ChangeStatus.toDbString(tc.status),
    reviewedAt = tc.reviewedAt,
    reviewedBy = tc.reviewedBy,
    reviewNotes = tc.reviewNotes,
    createdAt = tc.createdAt,
    appliedAt = tc.appliedAt,
    ambiguityType = tc.ambiguityType,
    ambiguityConfidence = tc.ambiguityConfidence
  )

  private def toComment(row: ChangeSetCommentRow): ChangeSetComment = ChangeSetComment(
    id = row.id,
    changeSetId = row.changeSetId,
    treeChangeId = row.treeChangeId,
    author = row.author,
    content = row.content,
    createdAt = row.createdAt,
    updatedAt = row.updatedAt
  )

  // ============================================================================
  // Change Set Implementations
  // ============================================================================

  override def createChangeSet(changeSet: ChangeSet): Future[Int] = {
    import java.sql.Timestamp

    val sql = """
      INSERT INTO tree.change_set (
        haplogroup_type, name, description, source_name, created_at, created_by,
        finalized_at, applied_at, applied_by, discarded_at, discarded_by, discard_reason,
        status, nodes_processed, nodes_created, nodes_updated, nodes_unchanged,
        variants_added, relationships_created, relationships_updated, split_operations,
        ambiguity_count, ambiguity_report_path, metadata
      ) VALUES (
        ?, ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?, ?,
        CAST(? AS tree.change_set_status), ?, ?, ?, ?,
        ?, ?, ?, ?,
        ?, ?, NULL
      ) RETURNING id
    """
    executeInsertReturningId(sql) { ps =>
      ps.setString(1, changeSet.haplogroupType.toString)
      ps.setString(2, changeSet.name)
      ps.setString(3, changeSet.description.orNull)
      ps.setString(4, changeSet.sourceName)
      ps.setTimestamp(5, Timestamp.valueOf(changeSet.createdAt))
      ps.setString(6, changeSet.createdBy)
      ps.setTimestamp(7, changeSet.finalizedAt.map(Timestamp.valueOf).orNull)
      ps.setTimestamp(8, changeSet.appliedAt.map(Timestamp.valueOf).orNull)
      ps.setString(9, changeSet.appliedBy.orNull)
      ps.setTimestamp(10, changeSet.discardedAt.map(Timestamp.valueOf).orNull)
      ps.setString(11, changeSet.discardedBy.orNull)
      ps.setString(12, changeSet.discardReason.orNull)
      ps.setString(13, ChangeSetStatus.toDbString(changeSet.status))
      ps.setInt(14, changeSet.statistics.nodesProcessed)
      ps.setInt(15, changeSet.statistics.nodesCreated)
      ps.setInt(16, changeSet.statistics.nodesUpdated)
      ps.setInt(17, changeSet.statistics.nodesUnchanged)
      ps.setInt(18, changeSet.statistics.variantsAdded)
      ps.setInt(19, changeSet.statistics.relationshipsCreated)
      ps.setInt(20, changeSet.statistics.relationshipsUpdated)
      ps.setInt(21, changeSet.statistics.splitOperations)
      ps.setInt(22, changeSet.statistics.ambiguityCount)
      ps.setString(23, changeSet.ambiguityReportPath.orNull)
    }
  }

  override def getChangeSet(id: Int): Future[Option[ChangeSet]] = {
    val query = changeSets.filter(_.id === id).result.headOption
    runQuery(query).map(_.map(toChangeSet))
  }

  override def getChangeSetByName(name: String, haplogroupType: HaplogroupType): Future[Option[ChangeSet]] = {
    val query = changeSets
      .filter(cs => cs.name === name && cs.haplogroupType === haplogroupType)
      .result.headOption
    runQuery(query).map(_.map(toChangeSet))
  }

  override def getActiveChangeSet(haplogroupType: HaplogroupType): Future[Option[ChangeSet]] = {
    val activeStatuses = Seq("DRAFT", "READY_FOR_REVIEW", "UNDER_REVIEW")
    val query = changeSets
      .filter(cs => cs.haplogroupType === haplogroupType && cs.status.inSet(activeStatuses))
      .sortBy(_.createdAt.desc)
      .result.headOption
    runQuery(query).map(_.map(toChangeSet))
  }

  override def listChangeSets(
    haplogroupType: Option[HaplogroupType],
    status: Option[ChangeSetStatus],
    limit: Int,
    offset: Int
  ): Future[Seq[ChangeSet]] = {
    val baseQuery = changeSets

    val filteredByType = haplogroupType match {
      case Some(ht) => baseQuery.filter(_.haplogroupType === ht)
      case None => baseQuery
    }

    val filteredByStatus = status match {
      case Some(s) => filteredByType.filter(_.status === ChangeSetStatus.toDbString(s))
      case None => filteredByType
    }

    val paginatedQuery = filteredByStatus.sortBy(_.createdAt.desc).drop(offset).take(limit).result
    runQuery(paginatedQuery).map(_.map(toChangeSet))
  }

  override def countChangeSets(
    haplogroupType: Option[HaplogroupType],
    status: Option[ChangeSetStatus]
  ): Future[Int] = {
    val baseQuery = changeSets

    val filteredByType = haplogroupType match {
      case Some(ht) => baseQuery.filter(_.haplogroupType === ht)
      case None => baseQuery
    }

    val filteredByStatus = status match {
      case Some(s) => filteredByType.filter(_.status === ChangeSetStatus.toDbString(s))
      case None => filteredByType
    }

    runQuery(filteredByStatus.length.result)
  }

  override def updateChangeSet(changeSet: ChangeSet): Future[Boolean] = {
    changeSet.id match {
      case Some(id) =>
        val row = toChangeSetRow(changeSet)
        val query = changeSets.filter(_.id === id).update(row)
        runQuery(query).map(_ > 0)
      case None =>
        Future.successful(false)
    }
  }

  override def updateChangeSetStatus(id: Int, status: ChangeSetStatus): Future[Boolean] = {
    executeUpdate("UPDATE tree.change_set SET status = CAST(? AS tree.change_set_status) WHERE id = ?") { ps =>
      ps.setString(1, ChangeSetStatus.toDbString(status))
      ps.setInt(2, id)
    }
  }

  override def finalizeChangeSet(
    id: Int,
    statistics: ChangeSetStatistics,
    ambiguityReportPath: Option[String]
  ): Future[Boolean] = {
    import java.sql.Timestamp
    val now = LocalDateTime.now()
    val sql = """
      UPDATE tree.change_set
      SET status = CAST('READY_FOR_REVIEW' AS tree.change_set_status),
          finalized_at = ?,
          nodes_processed = ?,
          nodes_created = ?,
          nodes_updated = ?,
          nodes_unchanged = ?,
          variants_added = ?,
          relationships_created = ?,
          relationships_updated = ?,
          split_operations = ?,
          ambiguity_count = ?,
          ambiguity_report_path = ?
      WHERE id = ?
    """
    executeUpdate(sql) { ps =>
      ps.setTimestamp(1, Timestamp.valueOf(now))
      ps.setInt(2, statistics.nodesProcessed)
      ps.setInt(3, statistics.nodesCreated)
      ps.setInt(4, statistics.nodesUpdated)
      ps.setInt(5, statistics.nodesUnchanged)
      ps.setInt(6, statistics.variantsAdded)
      ps.setInt(7, statistics.relationshipsCreated)
      ps.setInt(8, statistics.relationshipsUpdated)
      ps.setInt(9, statistics.splitOperations)
      ps.setInt(10, statistics.ambiguityCount)
      ps.setString(11, ambiguityReportPath.orNull)
      ps.setInt(12, id)
    }
  }

  override def applyChangeSet(id: Int, appliedBy: String): Future[Boolean] = {
    import java.sql.Timestamp
    val now = LocalDateTime.now()
    val sql = """
      UPDATE tree.change_set
      SET status = CAST('APPLIED' AS tree.change_set_status),
          applied_at = ?,
          applied_by = ?
      WHERE id = ?
    """
    executeUpdate(sql) { ps =>
      ps.setTimestamp(1, Timestamp.valueOf(now))
      ps.setString(2, appliedBy)
      ps.setInt(3, id)
    }
  }

  override def discardChangeSet(id: Int, discardedBy: String, reason: String): Future[Boolean] = {
    import java.sql.Timestamp
    val now = LocalDateTime.now()
    val sql = """
      UPDATE tree.change_set
      SET status = CAST('DISCARDED' AS tree.change_set_status),
          discarded_at = ?,
          discarded_by = ?,
          discard_reason = ?
      WHERE id = ?
    """
    executeUpdate(sql) { ps =>
      ps.setTimestamp(1, Timestamp.valueOf(now))
      ps.setString(2, discardedBy)
      ps.setString(3, reason)
      ps.setInt(4, id)
    }
  }

  // ============================================================================
  // Tree Change Implementations
  // ============================================================================

  override def createTreeChange(change: TreeChange): Future[Int] = {
    import java.sql.{Timestamp, Types}

    val sql = """
      INSERT INTO tree.tree_change (
        change_set_id, change_type, haplogroup_id, variant_id, old_parent_id, new_parent_id,
        haplogroup_data, old_data, created_haplogroup_id, sequence_num, status,
        reviewed_at, reviewed_by, review_notes, created_at, applied_at,
        ambiguity_type, ambiguity_confidence
      ) VALUES (
        ?, CAST(? AS tree.tree_change_type), ?, ?, ?, ?,
        CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS tree.change_status),
        ?, ?, ?, ?, ?,
        ?, ?
      ) RETURNING id
    """
    executeInsertReturningId(sql) { ps =>
      ps.setInt(1, change.changeSetId)
      ps.setString(2, TreeChangeType.toDbString(change.changeType))
      change.haplogroupId.fold(ps.setNull(3, Types.INTEGER))(v => ps.setInt(3, v))
      change.variantId.fold(ps.setNull(4, Types.INTEGER))(v => ps.setInt(4, v))
      change.oldParentId.fold(ps.setNull(5, Types.INTEGER))(v => ps.setInt(5, v))
      change.newParentId.fold(ps.setNull(6, Types.INTEGER))(v => ps.setInt(6, v))
      ps.setString(7, change.haplogroupData.orNull)
      ps.setString(8, change.oldData.orNull)
      change.createdHaplogroupId.fold(ps.setNull(9, Types.INTEGER))(v => ps.setInt(9, v))
      ps.setInt(10, change.sequenceNum)
      ps.setString(11, ChangeStatus.toDbString(change.status))
      change.reviewedAt.fold(ps.setNull(12, Types.TIMESTAMP))(v => ps.setTimestamp(12, Timestamp.valueOf(v)))
      ps.setString(13, change.reviewedBy.orNull)
      ps.setString(14, change.reviewNotes.orNull)
      ps.setTimestamp(15, Timestamp.valueOf(change.createdAt))
      change.appliedAt.fold(ps.setNull(16, Types.TIMESTAMP))(v => ps.setTimestamp(16, Timestamp.valueOf(v)))
      ps.setString(17, change.ambiguityType.orNull)
      change.ambiguityConfidence.fold(ps.setNull(18, Types.DOUBLE))(v => ps.setDouble(18, v))
    }
  }

  override def createTreeChanges(changes: Seq[TreeChange]): Future[Seq[Int]] = {
    // For bulk inserts, insert one at a time using raw SQL
    // This is less efficient but ensures proper enum casting
    Future.sequence(changes.map(createTreeChange))
  }

  override def getTreeChange(id: Int): Future[Option[TreeChange]] = {
    val query = treeChanges.filter(_.id === id).result.headOption
    runQuery(query).map(_.map(toTreeChange))
  }

  override def listTreeChanges(
    changeSetId: Int,
    changeType: Option[TreeChangeType],
    status: Option[ChangeStatus],
    limit: Int,
    offset: Int
  ): Future[Seq[TreeChange]] = {
    val baseQuery = treeChanges.filter(_.changeSetId === changeSetId)

    val filteredByType = changeType match {
      case Some(ct) => baseQuery.filter(_.changeType === TreeChangeType.toDbString(ct))
      case None => baseQuery
    }

    val filteredByStatus = status match {
      case Some(s) => filteredByType.filter(_.status === ChangeStatus.toDbString(s))
      case None => filteredByType
    }

    val paginatedQuery = filteredByStatus.sortBy(_.sequenceNum).drop(offset).take(limit).result
    runQuery(paginatedQuery).map(_.map(toTreeChange))
  }

  override def countTreeChanges(
    changeSetId: Int,
    changeType: Option[TreeChangeType],
    status: Option[ChangeStatus]
  ): Future[Int] = {
    val baseQuery = treeChanges.filter(_.changeSetId === changeSetId)

    val filteredByType = changeType match {
      case Some(ct) => baseQuery.filter(_.changeType === TreeChangeType.toDbString(ct))
      case None => baseQuery
    }

    val filteredByStatus = status match {
      case Some(s) => filteredByType.filter(_.status === ChangeStatus.toDbString(s))
      case None => filteredByType
    }

    runQuery(filteredByStatus.length.result)
  }

  override def getNextSequenceNum(changeSetId: Int): Future[Int] = {
    val query = treeChanges
      .filter(_.changeSetId === changeSetId)
      .map(_.sequenceNum)
      .max
      .result
    runQuery(query).map(_.getOrElse(0) + 1)
  }

  override def updateTreeChange(change: TreeChange): Future[Boolean] = {
    change.id match {
      case Some(id) =>
        val row = toTreeChangeRow(change)
        val query = treeChanges.filter(_.id === id).update(row)
        runQuery(query).map(_ > 0)
      case None =>
        Future.successful(false)
    }
  }

  override def updateTreeChangeStatus(id: Int, status: ChangeStatus): Future[Boolean] = {
    executeUpdate("UPDATE tree.tree_change SET status = CAST(? AS tree.change_status) WHERE id = ?") { ps =>
      ps.setString(1, ChangeStatus.toDbString(status))
      ps.setInt(2, id)
    }
  }

  override def reviewTreeChange(
    id: Int,
    reviewedBy: String,
    notes: Option[String],
    newStatus: ChangeStatus
  ): Future[Boolean] = {
    import java.sql.Timestamp
    val now = LocalDateTime.now()
    val sql = """
      UPDATE tree.tree_change
      SET status = CAST(? AS tree.change_status),
          reviewed_at = ?,
          reviewed_by = ?,
          review_notes = ?
      WHERE id = ?
    """
    executeUpdate(sql) { ps =>
      ps.setString(1, ChangeStatus.toDbString(newStatus))
      ps.setTimestamp(2, Timestamp.valueOf(now))
      ps.setString(3, reviewedBy)
      ps.setString(4, notes.orNull)
      ps.setInt(5, id)
    }
  }

  override def applyAllPendingChanges(changeSetId: Int): Future[Int] = {
    import java.sql.Timestamp
    val now = LocalDateTime.now()
    val sql = """
      UPDATE tree.tree_change
      SET status = CAST('APPLIED' AS tree.change_status),
          applied_at = ?
      WHERE change_set_id = ? AND status = 'PENDING'
    """
    executeUpdateCount(sql) { ps =>
      ps.setTimestamp(1, Timestamp.valueOf(now))
      ps.setInt(2, changeSetId)
    }
  }

  override def getPendingReviewChanges(changeSetId: Int, limit: Int): Future[Seq[TreeChange]] = {
    val query = treeChanges
      .filter(tc => tc.changeSetId === changeSetId && tc.status === "PENDING")
      .sortBy(tc => (tc.ambiguityConfidence.asc.nullsLast, tc.sequenceNum))
      .take(limit)
      .result
    runQuery(query).map(_.map(toTreeChange))
  }

  override def getChangesForChangeSet(changeSetId: Int): Future[Seq[TreeChange]] = {
    val query = treeChanges
      .filter(_.changeSetId === changeSetId)
      .sortBy(_.sequenceNum)
      .result
    runQuery(query).map(_.map(toTreeChange))
  }

  override def getChangeSummaryByType(changeSetId: Int): Future[Map[TreeChangeType, Int]] = {
    val query = treeChanges
      .filter(_.changeSetId === changeSetId)
      .groupBy(_.changeType)
      .map { case (changeType, group) => (changeType, group.length) }
      .result
    runQuery(query).map(_.map { case (ct, count) =>
      TreeChangeType.fromString(ct) -> count
    }.toMap)
  }

  override def getChangeSummaryByStatus(changeSetId: Int): Future[Map[ChangeStatus, Int]] = {
    val query = treeChanges
      .filter(_.changeSetId === changeSetId)
      .groupBy(_.status)
      .map { case (status, group) => (status, group.length) }
      .result
    runQuery(query).map(_.map { case (s, count) =>
      ChangeStatus.fromString(s) -> count
    }.toMap)
  }

  // ============================================================================
  // Comment Implementations
  // ============================================================================

  override def addComment(comment: ChangeSetComment): Future[Int] = {
    val row = ChangeSetCommentRow(
      id = comment.id,
      changeSetId = comment.changeSetId,
      treeChangeId = comment.treeChangeId,
      author = comment.author,
      content = comment.content,
      createdAt = comment.createdAt,
      updatedAt = comment.updatedAt
    )
    val query = (changeSetComments returning changeSetComments.map(_.id)) += row
    runQuery(query)
  }

  override def listComments(changeSetId: Int): Future[Seq[ChangeSetComment]] = {
    val query = changeSetComments
      .filter(_.changeSetId === changeSetId)
      .sortBy(_.createdAt)
      .result
    runQuery(query).map(_.map(toComment))
  }

  override def listCommentsForChange(treeChangeId: Int): Future[Seq[ChangeSetComment]] = {
    val query = changeSetComments
      .filter(_.treeChangeId === treeChangeId)
      .sortBy(_.createdAt)
      .result
    runQuery(query).map(_.map(toComment))
  }
}
