package models.dal.domain.haplogroups

import models.HaplogroupType
import models.dal.MyPostgresProfile.api.*
import models.domain.haplogroups.HaplogroupProvenance
import slick.lifted.ProvenShape

import java.time.LocalDateTime

/**
 * Slick table definitions for WIP (Work In Progress) tree staging tables.
 *
 * These tables hold staged tree changes during merge operations before
 * they are applied to production. Each table is scoped by change_set_id
 * for easy cleanup on discard.
 */

// ============================================================================
// Row Case Classes
// ============================================================================

case class WipHaplogroupRow(
  id: Option[Int],
  changeSetId: Int,
  placeholderId: Int,
  name: String,
  lineage: Option[String],
  description: Option[String],
  haplogroupType: HaplogroupType,
  source: String,
  confidenceLevel: String,
  formedYbp: Option[Int],
  formedYbpLower: Option[Int],
  formedYbpUpper: Option[Int],
  tmrcaYbp: Option[Int],
  tmrcaYbpLower: Option[Int],
  tmrcaYbpUpper: Option[Int],
  ageEstimateSource: Option[String],
  provenance: Option[HaplogroupProvenance], // JSONB via custom column mapper
  createdAt: LocalDateTime
)

case class WipRelationshipRow(
  id: Option[Int],
  changeSetId: Int,
  childHaplogroupId: Option[Int],
  childPlaceholderId: Option[Int],
  parentHaplogroupId: Option[Int],
  parentPlaceholderId: Option[Int],
  source: String,
  createdAt: LocalDateTime
)

case class WipHaplogroupVariantRow(
  id: Option[Int],
  changeSetId: Int,
  haplogroupId: Option[Int],
  haplogroupPlaceholderId: Option[Int],
  variantId: Int,
  source: Option[String],
  createdAt: LocalDateTime
)

case class WipReparentRow(
  id: Option[Int],
  changeSetId: Int,
  haplogroupId: Int,
  oldParentId: Option[Int],
  newParentId: Option[Int],
  newParentPlaceholderId: Option[Int],
  source: String,
  createdAt: LocalDateTime
)

/**
 * Resolution types for curator conflict corrections.
 */
object ResolutionType extends Enumeration {
  type ResolutionType = Value
  val Reparent, EditVariants, MergeExisting, Defer = Value

  def fromString(s: String): ResolutionType = s.toUpperCase match {
    case "REPARENT" => Reparent
    case "EDIT_VARIANTS" => EditVariants
    case "MERGE_EXISTING" => MergeExisting
    case "DEFER" => Defer
    case _ => throw new IllegalArgumentException(s"Unknown resolution type: $s")
  }

  def toDbString(rt: ResolutionType): String = rt match {
    case Reparent => "REPARENT"
    case EditVariants => "EDIT_VARIANTS"
    case MergeExisting => "MERGE_EXISTING"
    case Defer => "DEFER"
  }
}

object ResolutionStatus extends Enumeration {
  type ResolutionStatus = Value
  val Pending, Applied, Cancelled = Value

  def fromString(s: String): ResolutionStatus = s.toUpperCase match {
    case "PENDING" => Pending
    case "APPLIED" => Applied
    case "CANCELLED" => Cancelled
    case _ => throw new IllegalArgumentException(s"Unknown resolution status: $s")
  }

  def toDbString(rs: ResolutionStatus): String = rs match {
    case Pending => "PENDING"
    case Applied => "APPLIED"
    case Cancelled => "CANCELLED"
  }
}

object DeferPriority extends Enumeration {
  type DeferPriority = Value
  val Low, Normal, High, Critical = Value

  def fromString(s: String): DeferPriority = s.toUpperCase match {
    case "LOW" => Low
    case "NORMAL" => Normal
    case "HIGH" => High
    case "CRITICAL" => Critical
    case _ => Normal
  }

  def toDbString(dp: DeferPriority): String = dp match {
    case Low => "LOW"
    case Normal => "NORMAL"
    case High => "HIGH"
    case Critical => "CRITICAL"
  }
}

case class WipResolutionRow(
  id: Option[Int],
  changeSetId: Int,
  wipHaplogroupId: Option[Int],
  wipReparentId: Option[Int],
  resolutionType: String,
  // REPARENT fields
  newParentId: Option[Int],
  newParentPlaceholderId: Option[Int],
  // MERGE_EXISTING fields
  mergeTargetId: Option[Int],
  // EDIT_VARIANTS fields (stored as JSON arrays)
  variantsToAdd: Option[String],      // JSON array of variant IDs
  variantsToRemove: Option[String],   // JSON array of variant IDs
  // DEFER fields
  deferReason: Option[String],
  deferPriority: String,
  // Curator tracking
  curatorId: String,
  curatorNotes: Option[String],
  // Status
  status: String,
  createdAt: LocalDateTime,
  appliedAt: Option[LocalDateTime]
)

// ============================================================================
// Table Definitions
// ============================================================================

class WipHaplogroupTable(tag: Tag) extends Table[WipHaplogroupRow](tag, Some("tree"), "wip_haplogroup") {
  def id = column[Int]("wip_haplogroup_id", O.PrimaryKey, O.AutoInc)
  def changeSetId = column[Int]("change_set_id")
  def placeholderId = column[Int]("placeholder_id")
  def name = column[String]("name")
  def lineage = column[Option[String]]("lineage")
  def description = column[Option[String]]("description")
  def haplogroupType = column[HaplogroupType]("haplogroup_type")
  def source = column[String]("source")
  def confidenceLevel = column[String]("confidence_level")
  def formedYbp = column[Option[Int]]("formed_ybp")
  def formedYbpLower = column[Option[Int]]("formed_ybp_lower")
  def formedYbpUpper = column[Option[Int]]("formed_ybp_upper")
  def tmrcaYbp = column[Option[Int]]("tmrca_ybp")
  def tmrcaYbpLower = column[Option[Int]]("tmrca_ybp_lower")
  def tmrcaYbpUpper = column[Option[Int]]("tmrca_ybp_upper")
  def ageEstimateSource = column[Option[String]]("age_estimate_source")
  def provenance = column[Option[HaplogroupProvenance]]("provenance")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[WipHaplogroupRow] = (
    id.?, changeSetId, placeholderId, name, lineage, description, haplogroupType,
    source, confidenceLevel, formedYbp, formedYbpLower, formedYbpUpper,
    tmrcaYbp, tmrcaYbpLower, tmrcaYbpUpper, ageEstimateSource, provenance, createdAt
  ).mapTo[WipHaplogroupRow]

  def changeSetFk = foreignKey("wip_haplogroup_change_set_fk", changeSetId, TableQuery[ChangeSetsTable])(_.id)
}

class WipRelationshipTable(tag: Tag) extends Table[WipRelationshipRow](tag, Some("tree"), "wip_haplogroup_relationship") {
  def id = column[Int]("wip_relationship_id", O.PrimaryKey, O.AutoInc)
  def changeSetId = column[Int]("change_set_id")
  def childHaplogroupId = column[Option[Int]]("child_haplogroup_id")
  def childPlaceholderId = column[Option[Int]]("child_placeholder_id")
  def parentHaplogroupId = column[Option[Int]]("parent_haplogroup_id")
  def parentPlaceholderId = column[Option[Int]]("parent_placeholder_id")
  def source = column[String]("source")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[WipRelationshipRow] = (
    id.?, changeSetId, childHaplogroupId, childPlaceholderId,
    parentHaplogroupId, parentPlaceholderId, source, createdAt
  ).mapTo[WipRelationshipRow]

  def changeSetFk = foreignKey("wip_relationship_change_set_fk", changeSetId, TableQuery[ChangeSetsTable])(_.id)
}

class WipHaplogroupVariantTable(tag: Tag) extends Table[WipHaplogroupVariantRow](tag, Some("tree"), "wip_haplogroup_variant") {
  def id = column[Int]("wip_haplogroup_variant_id", O.PrimaryKey, O.AutoInc)
  def changeSetId = column[Int]("change_set_id")
  def haplogroupId = column[Option[Int]]("haplogroup_id")
  def haplogroupPlaceholderId = column[Option[Int]]("haplogroup_placeholder_id")
  def variantId = column[Int]("variant_id")
  def source = column[Option[String]]("source")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[WipHaplogroupVariantRow] = (
    id.?, changeSetId, haplogroupId, haplogroupPlaceholderId, variantId, source, createdAt
  ).mapTo[WipHaplogroupVariantRow]

  def changeSetFk = foreignKey("wip_variant_change_set_fk", changeSetId, TableQuery[ChangeSetsTable])(_.id)
}

class WipReparentTable(tag: Tag) extends Table[WipReparentRow](tag, Some("tree"), "wip_reparent") {
  def id = column[Int]("wip_reparent_id", O.PrimaryKey, O.AutoInc)
  def changeSetId = column[Int]("change_set_id")
  def haplogroupId = column[Int]("haplogroup_id")
  def oldParentId = column[Option[Int]]("old_parent_id")
  def newParentId = column[Option[Int]]("new_parent_id")
  def newParentPlaceholderId = column[Option[Int]]("new_parent_placeholder_id")
  def source = column[String]("source")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[WipReparentRow] = (
    id.?, changeSetId, haplogroupId, oldParentId, newParentId, newParentPlaceholderId, source, createdAt
  ).mapTo[WipReparentRow]

  def changeSetFk = foreignKey("wip_reparent_change_set_fk", changeSetId, TableQuery[ChangeSetsTable])(_.id)
}

class WipResolutionTable(tag: Tag) extends Table[WipResolutionRow](tag, Some("tree"), "wip_resolution") {
  def id = column[Int]("resolution_id", O.PrimaryKey, O.AutoInc)
  def changeSetId = column[Int]("change_set_id")
  def wipHaplogroupId = column[Option[Int]]("wip_haplogroup_id")
  def wipReparentId = column[Option[Int]]("wip_reparent_id")
  def resolutionType = column[String]("resolution_type")
  def newParentId = column[Option[Int]]("new_parent_id")
  def newParentPlaceholderId = column[Option[Int]]("new_parent_placeholder_id")
  def mergeTargetId = column[Option[Int]]("merge_target_id")
  def variantsToAdd = column[Option[String]]("variants_to_add")
  def variantsToRemove = column[Option[String]]("variants_to_remove")
  def deferReason = column[Option[String]]("defer_reason")
  def deferPriority = column[String]("defer_priority")
  def curatorId = column[String]("curator_id")
  def curatorNotes = column[Option[String]]("curator_notes")
  def status = column[String]("status")
  def createdAt = column[LocalDateTime]("created_at")
  def appliedAt = column[Option[LocalDateTime]]("applied_at")

  def * : ProvenShape[WipResolutionRow] = (
    id.?, changeSetId, wipHaplogroupId, wipReparentId, resolutionType,
    newParentId, newParentPlaceholderId, mergeTargetId,
    variantsToAdd, variantsToRemove, deferReason, deferPriority,
    curatorId, curatorNotes, status, createdAt, appliedAt
  ).mapTo[WipResolutionRow]

  def changeSetFk = foreignKey("wip_resolution_change_set_fk", changeSetId, TableQuery[ChangeSetsTable])(_.id)
}
