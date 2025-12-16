package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.domain.genomics.VariantV2
import models.domain.curator.AuditLogEntry
import models.domain.haplogroups.{ChangeSet, ChangeSetStatus, Haplogroup, HaplogroupVariantMetadata, TreeChange}
import play.api.Logging
import play.api.libs.json.*
import repositories.{CuratorAuditRepository, HaplogroupVariantMetadataRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for managing curator audit logging.
 * Provides methods to log create, update, and delete actions for haplogroups and variants,
 * as well as retrieve audit history.
 */
@Singleton
class CuratorAuditService @Inject()(
    auditRepository: CuratorAuditRepository,
    haplogroupVariantMetadataRepository: HaplogroupVariantMetadataRepository
)(implicit ec: ExecutionContext) extends Logging {

  // JSON formats for domain objects
  private given Format[HaplogroupType] = Format(
    Reads.StringReads.map(s => HaplogroupType.fromString(s).getOrElse(HaplogroupType.Y)),
    Writes.StringWrites.contramap(_.toString)
  )

  private given Format[LocalDateTime] = Format(
    Reads.localDateTimeReads("yyyy-MM-dd'T'HH:mm:ss"),
    Writes.temporalWrites[LocalDateTime, java.time.format.DateTimeFormatter](
      java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
    )
  )

  private given Format[Haplogroup] = Json.format[Haplogroup]

  // Helper to convert VariantV2 to JSON for audit logging
  private def variantV2ToJson(variant: VariantV2): JsValue = Json.obj(
    "variantId" -> variant.variantId,
    "canonicalName" -> variant.canonicalName,
    "mutationType" -> variant.mutationType,
    "namingStatus" -> variant.namingStatus,
    "aliases" -> variant.aliases,
    "coordinates" -> variant.coordinates,
    "notes" -> variant.notes
  )

  // === Haplogroup Audit Methods ===

  /**
   * Log haplogroup creation.
   */
  def logHaplogroupCreate(
      userId: UUID,
      haplogroup: Haplogroup,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "haplogroup",
      entityId = haplogroup.id.getOrElse(0),
      action = "create",
      oldValue = None,
      newValue = Some(Json.toJson(haplogroup)),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log haplogroup update.
   */
  def logHaplogroupUpdate(
      userId: UUID,
      oldHaplogroup: Haplogroup,
      newHaplogroup: Haplogroup,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "haplogroup",
      entityId = oldHaplogroup.id.getOrElse(0),
      action = "update",
      oldValue = Some(Json.toJson(oldHaplogroup)),
      newValue = Some(Json.toJson(newHaplogroup)),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log haplogroup soft-delete.
   */
  def logHaplogroupDelete(
      userId: UUID,
      haplogroup: Haplogroup,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "haplogroup",
      entityId = haplogroup.id.getOrElse(0),
      action = "delete",
      oldValue = Some(Json.toJson(haplogroup)),
      newValue = None,
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  // === Variant Audit Methods ===

  /**
   * Log variant creation.
   */
  def logVariantCreate(
      userId: UUID,
      variant: VariantV2,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "variant",
      entityId = variant.variantId.getOrElse(0),
      action = "create",
      oldValue = None,
      newValue = Some(variantV2ToJson(variant)),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log variant update.
   */
  def logVariantUpdate(
      userId: UUID,
      oldVariant: VariantV2,
      newVariant: VariantV2,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "variant",
      entityId = oldVariant.variantId.getOrElse(0),
      action = "update",
      oldValue = Some(variantV2ToJson(oldVariant)),
      newValue = Some(variantV2ToJson(newVariant)),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log variant deletion.
   */
  def logVariantDelete(
      userId: UUID,
      variant: VariantV2,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "variant",
      entityId = variant.variantId.getOrElse(0),
      action = "delete",
      oldValue = Some(variantV2ToJson(variant)),
      newValue = None,
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  // === History Retrieval Methods ===

  /**
   * Get audit history for a specific haplogroup.
   */
  def getHaplogroupHistory(haplogroupId: Int): Future[Seq[AuditLogEntry]] = {
    auditRepository.getEntityHistory("haplogroup", haplogroupId)
  }

  /**
   * Get audit history for a specific variant.
   */
  def getVariantHistory(variantId: Int): Future[Seq[AuditLogEntry]] = {
    auditRepository.getEntityHistory("variant", variantId)
  }

  /**
   * Get recent audit actions across all entities.
   */
  def getRecentActions(limit: Int = 50, offset: Int = 0): Future[Seq[AuditLogEntry]] = {
    auditRepository.getRecentActions(limit, offset)
  }

  /**
   * Get audit actions by a specific user.
   */
  def getActionsByUser(userId: UUID, limit: Int = 50, offset: Int = 0): Future[Seq[AuditLogEntry]] = {
    auditRepository.getActionsByUser(userId, limit, offset)
  }

  // === Haplogroup-Variant Association Audit Methods ===

  /**
   * Log when a variant is added to a haplogroup.
   */
  def logVariantAddedToHaplogroup(
      author: String,
      haplogroupVariantId: Int,
      comment: Option[String] = None
  ): Future[Int] = {
    val metadata = HaplogroupVariantMetadata(
      haplogroup_variant_id = haplogroupVariantId,
      revision_id = 1,
      author = author,
      timestamp = LocalDateTime.now(),
      comment = comment.getOrElse("Added via curator interface"),
      change_type = "add",
      previous_revision_id = None
    )
    haplogroupVariantMetadataRepository.addVariantRevisionMetadata(metadata)
  }

  /**
   * Log when a variant is removed from a haplogroup.
   */
  def logVariantRemovedFromHaplogroup(
      author: String,
      haplogroupVariantId: Int,
      comment: Option[String] = None
  ): Future[Int] = {
    // Get the latest revision to link to
    haplogroupVariantMetadataRepository.getVariantRevisionHistory(haplogroupVariantId).flatMap { history =>
      val latestRevisionId = history.headOption.map(_._2.revision_id)
      val nextRevisionId = latestRevisionId.map(_ + 1).getOrElse(1)

      val metadata = HaplogroupVariantMetadata(
        haplogroup_variant_id = haplogroupVariantId,
        revision_id = nextRevisionId,
        author = author,
        timestamp = LocalDateTime.now(),
        comment = comment.getOrElse("Removed via curator interface"),
        change_type = "remove",
        previous_revision_id = latestRevisionId
      )
      haplogroupVariantMetadataRepository.addVariantRevisionMetadata(metadata)
    }
  }

  /**
   * Get revision history for a haplogroup-variant association.
   */
  def getHaplogroupVariantHistory(haplogroupVariantId: Int): Future[Seq[HaplogroupVariantMetadata]] = {
    haplogroupVariantMetadataRepository.getVariantRevisionHistory(haplogroupVariantId).map(_.map(_._2))
  }

  // === Tree Versioning Audit Methods ===

  /**
   * Log change set creation.
   */
  def logChangeSetCreate(
      curatorId: String,
      changeSet: ChangeSet,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val details = Json.obj(
      "changeSetId" -> changeSet.id,
      "name" -> changeSet.name,
      "haplogroupType" -> changeSet.haplogroupType.toString,
      "sourceName" -> changeSet.sourceName,
      "status" -> changeSet.status.toString
    )
    val entry = AuditLogEntry(
      userId = curatorIdToUuid(curatorId),
      entityType = "change_set",
      entityId = changeSet.id.getOrElse(0),
      action = "create",
      oldValue = None,
      newValue = Some(details),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log change set status transition.
   */
  def logChangeSetStatusChange(
      curatorId: String,
      changeSetId: Int,
      oldStatus: ChangeSetStatus,
      newStatus: ChangeSetStatus,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val entry = AuditLogEntry(
      userId = curatorIdToUuid(curatorId),
      entityType = "change_set",
      entityId = changeSetId,
      action = "status_change",
      oldValue = Some(Json.obj("status" -> oldStatus.toString)),
      newValue = Some(Json.obj("status" -> newStatus.toString)),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log change set applied to production.
   */
  def logChangeSetApply(
      curatorId: String,
      changeSet: ChangeSet,
      appliedChangesCount: Int,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val details = Json.obj(
      "changeSetId" -> changeSet.id,
      "name" -> changeSet.name,
      "haplogroupType" -> changeSet.haplogroupType.toString,
      "appliedChanges" -> appliedChangesCount,
      "statistics" -> Json.obj(
        "nodesCreated" -> changeSet.statistics.nodesCreated,
        "nodesUpdated" -> changeSet.statistics.nodesUpdated,
        "variantsAdded" -> changeSet.statistics.variantsAdded
      )
    )
    val entry = AuditLogEntry(
      userId = curatorIdToUuid(curatorId),
      entityType = "change_set",
      entityId = changeSet.id.getOrElse(0),
      action = "apply",
      oldValue = Some(Json.obj("status" -> ChangeSetStatus.UnderReview.toString)),
      newValue = Some(details),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log change set discarded.
   */
  def logChangeSetDiscard(
      curatorId: String,
      changeSet: ChangeSet,
      reason: String
  ): Future[AuditLogEntry] = {
    val details = Json.obj(
      "changeSetId" -> changeSet.id,
      "name" -> changeSet.name,
      "reason" -> reason
    )
    val entry = AuditLogEntry(
      userId = curatorIdToUuid(curatorId),
      entityType = "change_set",
      entityId = changeSet.id.getOrElse(0),
      action = "discard",
      oldValue = Some(Json.obj("status" -> changeSet.status.toString)),
      newValue = Some(details),
      comment = Some(reason)
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log individual change review action.
   */
  def logChangeReview(
      curatorId: String,
      change: TreeChange,
      action: String,
      notes: Option[String] = None
  ): Future[AuditLogEntry] = {
    val details = Json.obj(
      "changeId" -> change.id,
      "changeSetId" -> change.changeSetId,
      "changeType" -> change.changeType.toString,
      "reviewAction" -> action
    )
    val entry = AuditLogEntry(
      userId = curatorIdToUuid(curatorId),
      entityType = "tree_change",
      entityId = change.id.getOrElse(0),
      action = s"review_$action".toLowerCase,
      oldValue = Some(Json.obj("status" -> change.status.toString)),
      newValue = Some(details),
      comment = notes
    )
    auditRepository.logAction(entry)
  }

  /**
   * Get audit history for a change set.
   */
  def getChangeSetHistory(changeSetId: Int): Future[Seq[AuditLogEntry]] = {
    auditRepository.getEntityHistory("change_set", changeSetId)
  }

  // Helper to convert curator ID string to UUID
  private def curatorIdToUuid(curatorId: String): UUID = {
    try {
      UUID.fromString(curatorId)
    } catch {
      case _: IllegalArgumentException =>
        // Generate a deterministic UUID from the curator ID string
        UUID.nameUUIDFromBytes(curatorId.getBytes("UTF-8"))
    }
  }

  // === Tree Restructuring Audit Methods ===

  /**
   * Log a branch split operation.
   */
  def logBranchSplit(
      userId: UUID,
      parentId: Int,
      newHaplogroupId: Int,
      movedVariantCount: Int,
      movedChildIds: Seq[Int],
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val details = Json.obj(
      "operation" -> "split",
      "parentId" -> parentId,
      "newHaplogroupId" -> newHaplogroupId,
      "movedVariantCount" -> movedVariantCount,
      "movedChildIds" -> movedChildIds
    )
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "haplogroup",
      entityId = newHaplogroupId,
      action = "split",
      oldValue = None,
      newValue = Some(details),
      comment = comment
    )
    auditRepository.logAction(entry)
  }

  /**
   * Log a merge into parent operation.
   */
  def logMergeIntoParent(
      userId: UUID,
      parentId: Int,
      absorbedChildId: Int,
      movedVariantCount: Int,
      promotedChildCount: Int,
      comment: Option[String] = None
  ): Future[AuditLogEntry] = {
    val details = Json.obj(
      "operation" -> "merge",
      "parentId" -> parentId,
      "absorbedChildId" -> absorbedChildId,
      "movedVariantCount" -> movedVariantCount,
      "promotedChildCount" -> promotedChildCount
    )
    val entry = AuditLogEntry(
      userId = userId,
      entityType = "haplogroup",
      entityId = parentId,
      action = "merge",
      oldValue = Some(Json.obj("absorbedChildId" -> absorbedChildId)),
      newValue = Some(details),
      comment = comment
    )
    auditRepository.logAction(entry)
  }
}
