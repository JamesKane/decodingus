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
