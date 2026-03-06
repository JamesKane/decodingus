package models.dal.domain.discovery

import models.HaplogroupType
import models.dal.MyPostgresProfile.api.*
import models.dal.domain.haplogroups.HaplogroupsTable
import models.domain.discovery.*
import play.api.libs.json.JsValue
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

class BiosamplePrivateVariantTable(tag: Tag) extends Table[BiosamplePrivateVariant](tag, Some("tree"), "biosample_private_variant") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def sampleType = column[BiosampleSourceType]("sample_type")
  def sampleId = column[Int]("sample_id")
  def sampleGuid = column[UUID]("sample_guid")
  def variantId = column[Int]("variant_id")
  def haplogroupType = column[HaplogroupType]("haplogroup_type")
  def terminalHaplogroupId = column[Int]("terminal_haplogroup_id")
  def discoveredAt = column[LocalDateTime]("discovered_at")
  def status = column[PrivateVariantStatus]("status")

  def * : ProvenShape[BiosamplePrivateVariant] = (
    id.?, sampleType, sampleId, sampleGuid, variantId,
    haplogroupType, terminalHaplogroupId, discoveredAt, status
  ).mapTo[BiosamplePrivateVariant]

  def terminalHaplogroupFK = foreignKey("bpv_terminal_hg_fk", terminalHaplogroupId, TableQuery[HaplogroupsTable])(
    _.haplogroupId, onDelete = ForeignKeyAction.Restrict
  )

  def uniqueSampleVariant = index("idx_bpv_unique", (sampleType, sampleId, variantId, haplogroupType), unique = true)
}

class ProposedBranchTable(tag: Tag) extends Table[ProposedBranch](tag, Some("tree"), "proposed_branch") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def parentHaplogroupId = column[Int]("parent_haplogroup_id")
  def proposedName = column[Option[String]]("proposed_name")
  def haplogroupType = column[HaplogroupType]("haplogroup_type")
  def status = column[ProposedBranchStatus]("status")
  def consensusCount = column[Int]("consensus_count")
  def confidenceScore = column[Double]("confidence_score")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")
  def reviewedAt = column[Option[LocalDateTime]]("reviewed_at")
  def reviewedBy = column[Option[String]]("reviewed_by")
  def notes = column[Option[String]]("notes")
  def promotedHaplogroupId = column[Option[Int]]("promoted_haplogroup_id")

  def * : ProvenShape[ProposedBranch] = (
    id.?, parentHaplogroupId, proposedName, haplogroupType, status,
    consensusCount, confidenceScore, createdAt, updatedAt,
    reviewedAt, reviewedBy, notes, promotedHaplogroupId
  ).mapTo[ProposedBranch]

  def parentHaplogroupFK = foreignKey("pb_parent_hg_fk", parentHaplogroupId, TableQuery[HaplogroupsTable])(
    _.haplogroupId, onDelete = ForeignKeyAction.Restrict
  )
  def promotedHaplogroupFK = foreignKey("pb_promoted_hg_fk", promotedHaplogroupId, TableQuery[HaplogroupsTable])(
    _.haplogroupId.?, onDelete = ForeignKeyAction.SetNull
  )
}

class ProposedBranchVariantTable(tag: Tag) extends Table[ProposedBranchVariant](tag, Some("tree"), "proposed_branch_variant") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def proposedBranchId = column[Int]("proposed_branch_id")
  def variantId = column[Int]("variant_id")
  def isDefining = column[Boolean]("is_defining")
  def evidenceCount = column[Int]("evidence_count")
  def firstObservedAt = column[LocalDateTime]("first_observed_at")
  def lastObservedAt = column[LocalDateTime]("last_observed_at")

  def * : ProvenShape[ProposedBranchVariant] = (
    id.?, proposedBranchId, variantId, isDefining, evidenceCount,
    firstObservedAt, lastObservedAt
  ).mapTo[ProposedBranchVariant]

  def proposedBranchFK = foreignKey("pbv_branch_fk", proposedBranchId, TableQuery[ProposedBranchTable])(
    _.id, onDelete = ForeignKeyAction.Cascade
  )

  def uniqueBranchVariant = index("idx_pbv_unique", (proposedBranchId, variantId), unique = true)
}

class ProposedBranchEvidenceTable(tag: Tag) extends Table[ProposedBranchEvidence](tag, Some("tree"), "proposed_branch_evidence") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def proposedBranchId = column[Int]("proposed_branch_id")
  def sampleType = column[BiosampleSourceType]("sample_type")
  def sampleId = column[Int]("sample_id")
  def sampleGuid = column[UUID]("sample_guid")
  def addedAt = column[LocalDateTime]("added_at")
  def variantMatchCount = column[Int]("variant_match_count")
  def variantMismatchCount = column[Int]("variant_mismatch_count")

  def * : ProvenShape[ProposedBranchEvidence] = (
    id.?, proposedBranchId, sampleType, sampleId, sampleGuid,
    addedAt, variantMatchCount, variantMismatchCount
  ).mapTo[ProposedBranchEvidence]

  def proposedBranchFK = foreignKey("pbe_branch_fk", proposedBranchId, TableQuery[ProposedBranchTable])(
    _.id, onDelete = ForeignKeyAction.Cascade
  )

  def uniqueBranchSample = index("idx_pbe_unique", (proposedBranchId, sampleType, sampleId), unique = true)
}

class CuratorActionTable(tag: Tag) extends Table[CuratorAction](tag, Some("tree"), "curator_action") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def curatorId = column[String]("curator_id")
  def actionType = column[CuratorActionType]("action_type")
  def targetType = column[CuratorTargetType]("target_type")
  def targetId = column[Int]("target_id")
  def previousState = column[Option[JsValue]]("previous_state")
  def newState = column[Option[JsValue]]("new_state")
  def reason = column[Option[String]]("reason")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[CuratorAction] = (
    id.?, curatorId, actionType, targetType, targetId,
    previousState, newState, reason, createdAt
  ).mapTo[CuratorAction]
}

class DiscoveryConfigTable(tag: Tag) extends Table[DiscoveryConfig](tag, Some("tree"), "discovery_config") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def haplogroupType = column[HaplogroupType]("haplogroup_type")
  def configKey = column[String]("config_key")
  def configValue = column[String]("config_value")
  def description = column[Option[String]]("description")
  def updatedAt = column[LocalDateTime]("updated_at")
  def updatedBy = column[Option[String]]("updated_by")

  def * : ProvenShape[DiscoveryConfig] = (
    id.?, haplogroupType, configKey, configValue, description, updatedAt, updatedBy
  ).mapTo[DiscoveryConfig]

  def uniqueTypeKey = index("idx_dc_unique", (haplogroupType, configKey), unique = true)
}
