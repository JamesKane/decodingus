package models.domain.haplogroups

import models.HaplogroupType
import play.api.libs.json.{Format, Json, OFormat, Reads, Writes}

import java.time.LocalDateTime

/**
 * Domain models for the Tree Versioning System.
 *
 * Supports Production/WIP tree versioning for bulk merge operations.
 * Change sets track groups of changes from external sources (ISOGG, ytree.net).
 * Individual changes are recorded for curator review before promotion to Production.
 */

// ============================================================================
// Enums
// ============================================================================

/**
 * Status of a change set in its lifecycle.
 */
enum ChangeSetStatus:
  case Draft          // Being built (merge in progress)
  case ReadyForReview // Merge complete, awaiting curator review
  case UnderReview    // Curator actively reviewing
  case Applied        // Changes applied to Production
  case Discarded      // Changes abandoned

object ChangeSetStatus {
  def fromString(s: String): ChangeSetStatus = s.toUpperCase match {
    case "DRAFT" => ChangeSetStatus.Draft
    case "READY_FOR_REVIEW" => ChangeSetStatus.ReadyForReview
    case "UNDER_REVIEW" => ChangeSetStatus.UnderReview
    case "APPLIED" => ChangeSetStatus.Applied
    case "DISCARDED" => ChangeSetStatus.Discarded
    case other => throw new IllegalArgumentException(s"Unknown ChangeSetStatus: $other")
  }

  def toDbString(status: ChangeSetStatus): String = status match {
    case ChangeSetStatus.Draft => "DRAFT"
    case ChangeSetStatus.ReadyForReview => "READY_FOR_REVIEW"
    case ChangeSetStatus.UnderReview => "UNDER_REVIEW"
    case ChangeSetStatus.Applied => "APPLIED"
    case ChangeSetStatus.Discarded => "DISCARDED"
  }

  implicit val reads: Reads[ChangeSetStatus] = Reads.StringReads.map(fromString)
  implicit val writes: Writes[ChangeSetStatus] = Writes.StringWrites.contramap(toDbString)
  implicit val format: Format[ChangeSetStatus] = Format(reads, writes)
}

/**
 * Type of change recorded in a tree change.
 */
enum TreeChangeType:
  case Create        // New haplogroup created
  case Update        // Haplogroup metadata updated
  case Delete        // Haplogroup deleted (soft)
  case Reparent      // Parent relationship changed
  case AddVariant    // Variant associated with haplogroup
  case RemoveVariant // Variant disassociated from haplogroup

object TreeChangeType {
  def fromString(s: String): TreeChangeType = s.toUpperCase match {
    case "CREATE" => TreeChangeType.Create
    case "UPDATE" => TreeChangeType.Update
    case "DELETE" => TreeChangeType.Delete
    case "REPARENT" => TreeChangeType.Reparent
    case "ADD_VARIANT" => TreeChangeType.AddVariant
    case "REMOVE_VARIANT" => TreeChangeType.RemoveVariant
    case other => throw new IllegalArgumentException(s"Unknown TreeChangeType: $other")
  }

  def toDbString(changeType: TreeChangeType): String = changeType match {
    case TreeChangeType.Create => "CREATE"
    case TreeChangeType.Update => "UPDATE"
    case TreeChangeType.Delete => "DELETE"
    case TreeChangeType.Reparent => "REPARENT"
    case TreeChangeType.AddVariant => "ADD_VARIANT"
    case TreeChangeType.RemoveVariant => "REMOVE_VARIANT"
  }

  implicit val reads: Reads[TreeChangeType] = Reads.StringReads.map(fromString)
  implicit val writes: Writes[TreeChangeType] = Writes.StringWrites.contramap(toDbString)
  implicit val format: Format[TreeChangeType] = Format(reads, writes)
}

/**
 * Status of an individual tree change.
 */
enum ChangeStatus:
  case Pending   // Not yet applied
  case Applied   // Successfully applied to Production
  case Reverted  // Undone by curator
  case Skipped   // Excluded from promotion by curator

object ChangeStatus {
  def fromString(s: String): ChangeStatus = s.toUpperCase match {
    case "PENDING" => ChangeStatus.Pending
    case "APPLIED" => ChangeStatus.Applied
    case "REVERTED" => ChangeStatus.Reverted
    case "SKIPPED" => ChangeStatus.Skipped
    case other => throw new IllegalArgumentException(s"Unknown ChangeStatus: $other")
  }

  def toDbString(status: ChangeStatus): String = status match {
    case ChangeStatus.Pending => "PENDING"
    case ChangeStatus.Applied => "APPLIED"
    case ChangeStatus.Reverted => "REVERTED"
    case ChangeStatus.Skipped => "SKIPPED"
  }

  implicit val reads: Reads[ChangeStatus] = Reads.StringReads.map(fromString)
  implicit val writes: Writes[ChangeStatus] = Writes.StringWrites.contramap(toDbString)
  implicit val format: Format[ChangeStatus] = Format(reads, writes)
}

// ============================================================================
// Domain Models
// ============================================================================

/**
 * Statistics from a merge operation, stored with the change set.
 */
case class ChangeSetStatistics(
  nodesProcessed: Int = 0,
  nodesCreated: Int = 0,
  nodesUpdated: Int = 0,
  nodesUnchanged: Int = 0,
  variantsAdded: Int = 0,
  relationshipsCreated: Int = 0,
  relationshipsUpdated: Int = 0,
  splitOperations: Int = 0,
  ambiguityCount: Int = 0
)

object ChangeSetStatistics {
  implicit val format: OFormat[ChangeSetStatistics] = Json.format[ChangeSetStatistics]

  val empty: ChangeSetStatistics = ChangeSetStatistics()
}

/**
 * A change set groups related changes from a single merge operation.
 *
 * Lifecycle:
 *   DRAFT -> READY_FOR_REVIEW -> UNDER_REVIEW -> APPLIED
 *                            \-> DISCARDED
 *
 * @param id Unique identifier
 * @param haplogroupType Y or MT tree
 * @param name Unique name within type (e.g., "isogg-2025-12")
 * @param description Optional description of the change set
 * @param sourceName Source of the changes (e.g., "ISOGG", "ytree.net")
 * @param createdAt When the change set was created
 * @param createdBy Who created it (curator ID or "system")
 * @param finalizedAt When merge completed and set moved to READY_FOR_REVIEW
 * @param appliedAt When changes were applied to Production
 * @param appliedBy Who applied the changes
 * @param discardedAt When changes were discarded
 * @param discardedBy Who discarded the changes
 * @param discardReason Why the changes were discarded
 * @param status Current lifecycle status
 * @param statistics Merge statistics snapshot
 * @param ambiguityReportPath Path to generated ambiguity report file
 */
case class ChangeSet(
  id: Option[Int],
  haplogroupType: HaplogroupType,
  name: String,
  description: Option[String],
  sourceName: String,
  createdAt: LocalDateTime,
  createdBy: String,
  finalizedAt: Option[LocalDateTime] = None,
  appliedAt: Option[LocalDateTime] = None,
  appliedBy: Option[String] = None,
  discardedAt: Option[LocalDateTime] = None,
  discardedBy: Option[String] = None,
  discardReason: Option[String] = None,
  status: ChangeSetStatus = ChangeSetStatus.Draft,
  statistics: ChangeSetStatistics = ChangeSetStatistics.empty,
  ambiguityReportPath: Option[String] = None
)

object ChangeSet {
  implicit val format: OFormat[ChangeSet] = Json.format[ChangeSet]
}

/**
 * An individual change within a change set.
 *
 * Tracks a single operation (create, update, reparent, etc.) for audit and review.
 *
 * @param id Unique identifier
 * @param changeSetId Parent change set
 * @param changeType Type of change
 * @param haplogroupId Target haplogroup (for UPDATE/DELETE/REPARENT)
 * @param variantId Target variant (for ADD_VARIANT/REMOVE_VARIANT)
 * @param oldParentId Previous parent (for REPARENT)
 * @param newParentId New parent (for CREATE and REPARENT)
 * @param haplogroupData Full haplogroup data for CREATE, or new values for UPDATE
 * @param oldData Previous state for UPDATE (audit trail)
 * @param createdHaplogroupId For CREATE, the ID assigned after apply
 * @param sequenceNum Order within change set (for replay)
 * @param status Current status of this change
 * @param reviewedAt When curator reviewed this change
 * @param reviewedBy Who reviewed it
 * @param reviewNotes Curator's notes
 * @param createdAt When change was recorded
 * @param appliedAt When change was applied to Production
 * @param ambiguityType If this relates to an ambiguity, the type
 * @param ambiguityConfidence If this relates to an ambiguity, the confidence score
 */
case class TreeChange(
  id: Option[Int],
  changeSetId: Int,
  changeType: TreeChangeType,
  haplogroupId: Option[Int] = None,
  variantId: Option[Int] = None,
  oldParentId: Option[Int] = None,
  newParentId: Option[Int] = None,
  haplogroupData: Option[String] = None, // JSON string
  oldData: Option[String] = None,        // JSON string
  createdHaplogroupId: Option[Int] = None,
  sequenceNum: Int,
  status: ChangeStatus = ChangeStatus.Pending,
  reviewedAt: Option[LocalDateTime] = None,
  reviewedBy: Option[String] = None,
  reviewNotes: Option[String] = None,
  createdAt: LocalDateTime = LocalDateTime.now(),
  appliedAt: Option[LocalDateTime] = None,
  ambiguityType: Option[String] = None,
  ambiguityConfidence: Option[Double] = None
)

object TreeChange {
  implicit val format: OFormat[TreeChange] = Json.format[TreeChange]
}

/**
 * A comment on a change set or specific change for curator collaboration.
 */
case class ChangeSetComment(
  id: Option[Int],
  changeSetId: Int,
  treeChangeId: Option[Int],
  author: String,
  content: String,
  createdAt: LocalDateTime,
  updatedAt: Option[LocalDateTime] = None
)

object ChangeSetComment {
  implicit val format: OFormat[ChangeSetComment] = Json.format[ChangeSetComment]
}

// ============================================================================
// View Models for API responses
// ============================================================================

/**
 * Summary view of a change set for list displays.
 */
case class ChangeSetSummary(
  id: Int,
  haplogroupType: HaplogroupType,
  name: String,
  sourceName: String,
  status: ChangeSetStatus,
  createdAt: LocalDateTime,
  createdBy: String,
  statistics: ChangeSetStatistics,
  totalChanges: Int,
  pendingChanges: Int,
  reviewedChanges: Int
)

object ChangeSetSummary {
  implicit val format: OFormat[ChangeSetSummary] = Json.format[ChangeSetSummary]
}

/**
 * Detailed view of a change set including all metadata.
 */
case class ChangeSetDetails(
  changeSet: ChangeSet,
  totalChanges: Int,
  changesByType: Map[String, Int],   // String keys for JSON compatibility
  changesByStatus: Map[String, Int], // String keys for JSON compatibility
  comments: List[ChangeSetComment]
)

object ChangeSetDetails {
  implicit val format: OFormat[ChangeSetDetails] = Json.format[ChangeSetDetails]
}

/**
 * A tree change with additional context for review UI.
 */
case class TreeChangeView(
  change: TreeChange,
  changeSetName: String,
  sourceName: String,
  haplogroupName: Option[String],
  parentName: Option[String],
  variantName: Option[String]
)

object TreeChangeView {
  implicit val format: OFormat[TreeChangeView] = Json.format[TreeChangeView]
}
