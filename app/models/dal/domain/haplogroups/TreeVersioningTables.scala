package models.dal.domain.haplogroups

import models.HaplogroupType
import models.dal.MyPostgresProfile.api.*
import models.domain.haplogroups.{ChangeSetStatus, ChangeStatus, TreeChangeType}
import slick.lifted.ProvenShape

import java.time.LocalDateTime

/**
 * Slick table definitions for the Tree Versioning System.
 *
 * Supports Production/WIP tree versioning for bulk merge operations.
 */

// ============================================================================
// Row Case Classes
// ============================================================================

case class ChangeSetRow(
  id: Option[Int],
  haplogroupType: HaplogroupType,
  name: String,
  description: Option[String],
  sourceName: String,
  createdAt: LocalDateTime,
  createdBy: String,
  finalizedAt: Option[LocalDateTime],
  appliedAt: Option[LocalDateTime],
  appliedBy: Option[String],
  discardedAt: Option[LocalDateTime],
  discardedBy: Option[String],
  discardReason: Option[String],
  status: String,
  nodesProcessed: Int,
  nodesCreated: Int,
  nodesUpdated: Int,
  nodesUnchanged: Int,
  variantsAdded: Int,
  relationshipsCreated: Int,
  relationshipsUpdated: Int,
  splitOperations: Int,
  ambiguityCount: Int,
  ambiguityReportPath: Option[String],
  metadata: Option[String]
)

case class TreeChangeRow(
  id: Option[Int],
  changeSetId: Int,
  changeType: String,
  haplogroupId: Option[Int],
  variantId: Option[Int],
  oldParentId: Option[Int],
  newParentId: Option[Int],
  haplogroupData: Option[String],
  oldData: Option[String],
  createdHaplogroupId: Option[Int],
  sequenceNum: Int,
  status: String,
  reviewedAt: Option[LocalDateTime],
  reviewedBy: Option[String],
  reviewNotes: Option[String],
  createdAt: LocalDateTime,
  appliedAt: Option[LocalDateTime],
  ambiguityType: Option[String],
  ambiguityConfidence: Option[Double]
)

case class ChangeSetCommentRow(
  id: Option[Int],
  changeSetId: Int,
  treeChangeId: Option[Int],
  author: String,
  content: String,
  createdAt: LocalDateTime,
  updatedAt: Option[LocalDateTime]
)

// ============================================================================
// Table Definitions
// ============================================================================

class ChangeSetsTable(tag: Tag) extends Table[ChangeSetRow](tag, Some("tree"), "change_set") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def haplogroupType = column[HaplogroupType]("haplogroup_type")
  def name = column[String]("name")
  def description = column[Option[String]]("description")
  def sourceName = column[String]("source_name")
  def createdAt = column[LocalDateTime]("created_at")
  def createdBy = column[String]("created_by")
  def finalizedAt = column[Option[LocalDateTime]]("finalized_at")
  def appliedAt = column[Option[LocalDateTime]]("applied_at")
  def appliedBy = column[Option[String]]("applied_by")
  def discardedAt = column[Option[LocalDateTime]]("discarded_at")
  def discardedBy = column[Option[String]]("discarded_by")
  def discardReason = column[Option[String]]("discard_reason")
  def status = column[String]("status")
  def nodesProcessed = column[Int]("nodes_processed")
  def nodesCreated = column[Int]("nodes_created")
  def nodesUpdated = column[Int]("nodes_updated")
  def nodesUnchanged = column[Int]("nodes_unchanged")
  def variantsAdded = column[Int]("variants_added")
  def relationshipsCreated = column[Int]("relationships_created")
  def relationshipsUpdated = column[Int]("relationships_updated")
  def splitOperations = column[Int]("split_operations")
  def ambiguityCount = column[Int]("ambiguity_count")
  def ambiguityReportPath = column[Option[String]]("ambiguity_report_path")
  def metadata = column[Option[String]]("metadata")

  // Split into nested tuples to work around 22-column limit
  private type CoreFields = (Option[Int], HaplogroupType, String, Option[String], String,
    LocalDateTime, String, Option[LocalDateTime], Option[LocalDateTime], Option[String],
    Option[LocalDateTime], Option[String], Option[String], String)
  private type StatsFields = (Int, Int, Int, Int, Int, Int, Int, Int, Int, Option[String], Option[String])

  def * : ProvenShape[ChangeSetRow] = (
    (id.?, haplogroupType, name, description, sourceName, createdAt, createdBy,
      finalizedAt, appliedAt, appliedBy, discardedAt, discardedBy, discardReason, status),
    (nodesProcessed, nodesCreated, nodesUpdated, nodesUnchanged, variantsAdded,
      relationshipsCreated, relationshipsUpdated, splitOperations, ambiguityCount,
      ambiguityReportPath, metadata)
  ).<>(
    { case (core: CoreFields, stats: StatsFields) =>
      ChangeSetRow(
        core._1, core._2, core._3, core._4, core._5, core._6, core._7,
        core._8, core._9, core._10, core._11, core._12, core._13, core._14,
        stats._1, stats._2, stats._3, stats._4, stats._5, stats._6, stats._7,
        stats._8, stats._9, stats._10, stats._11
      )
    },
    { (row: ChangeSetRow) =>
      Some((
        (row.id, row.haplogroupType, row.name, row.description, row.sourceName, row.createdAt,
          row.createdBy, row.finalizedAt, row.appliedAt, row.appliedBy, row.discardedAt,
          row.discardedBy, row.discardReason, row.status),
        (row.nodesProcessed, row.nodesCreated, row.nodesUpdated, row.nodesUnchanged, row.variantsAdded,
          row.relationshipsCreated, row.relationshipsUpdated, row.splitOperations, row.ambiguityCount,
          row.ambiguityReportPath, row.metadata)
      ))
    }
  )

  def nameIdx = index("idx_change_set_name", (haplogroupType, name), unique = true)
  def statusIdx = index("idx_change_set_status", status)
  def typeIdx = index("idx_change_set_type", haplogroupType)
}

class TreeChangesTable(tag: Tag) extends Table[TreeChangeRow](tag, Some("tree"), "tree_change") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def changeSetId = column[Int]("change_set_id")
  def changeType = column[String]("change_type")
  def haplogroupId = column[Option[Int]]("haplogroup_id")
  def variantId = column[Option[Int]]("variant_id")
  def oldParentId = column[Option[Int]]("old_parent_id")
  def newParentId = column[Option[Int]]("new_parent_id")
  def haplogroupData = column[Option[String]]("haplogroup_data")
  def oldData = column[Option[String]]("old_data")
  def createdHaplogroupId = column[Option[Int]]("created_haplogroup_id")
  def sequenceNum = column[Int]("sequence_num")
  def status = column[String]("status")
  def reviewedAt = column[Option[LocalDateTime]]("reviewed_at")
  def reviewedBy = column[Option[String]]("reviewed_by")
  def reviewNotes = column[Option[String]]("review_notes")
  def createdAt = column[LocalDateTime]("created_at")
  def appliedAt = column[Option[LocalDateTime]]("applied_at")
  def ambiguityType = column[Option[String]]("ambiguity_type")
  def ambiguityConfidence = column[Option[Double]]("ambiguity_confidence")

  def * : ProvenShape[TreeChangeRow] = (
    id.?,
    changeSetId,
    changeType,
    haplogroupId,
    variantId,
    oldParentId,
    newParentId,
    haplogroupData,
    oldData,
    createdHaplogroupId,
    sequenceNum,
    status,
    reviewedAt,
    reviewedBy,
    reviewNotes,
    createdAt,
    appliedAt,
    ambiguityType,
    ambiguityConfidence
  ).mapTo[TreeChangeRow]

  def changeSetIdx = index("idx_tree_change_set", changeSetId)
  def haplogroupIdx = index("idx_tree_change_hg", haplogroupId)
  def statusIdx = index("idx_tree_change_status", status)
  def seqIdx = index("idx_tree_change_seq", (changeSetId, sequenceNum))

  def changeSetFk = foreignKey("fk_tree_change_set", changeSetId, TableQuery[ChangeSetsTable])(_.id, onDelete = ForeignKeyAction.Cascade)
}

class ChangeSetCommentsTable(tag: Tag) extends Table[ChangeSetCommentRow](tag, Some("tree"), "change_set_comment") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def changeSetId = column[Int]("change_set_id")
  def treeChangeId = column[Option[Int]]("tree_change_id")
  def author = column[String]("author")
  def content = column[String]("content")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[Option[LocalDateTime]]("updated_at")

  def * : ProvenShape[ChangeSetCommentRow] = (
    id.?,
    changeSetId,
    treeChangeId,
    author,
    content,
    createdAt,
    updatedAt
  ).mapTo[ChangeSetCommentRow]

  def changeSetIdx = index("idx_change_set_comment_set", changeSetId)
  def treeChangeIdx = index("idx_change_set_comment_change", treeChangeId)

  def changeSetFk = foreignKey("fk_comment_change_set", changeSetId, TableQuery[ChangeSetsTable])(_.id, onDelete = ForeignKeyAction.Cascade)
  def treeChangeFk = foreignKey("fk_comment_tree_change", treeChangeId, TableQuery[TreeChangesTable])(_.id.?, onDelete = ForeignKeyAction.Cascade)
}
