package models.domain.curator

import play.api.libs.json.JsValue

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents an audit log entry for curator actions on haplogroups and variants.
 *
 * @param id         UUID Primary Key
 * @param userId     The user who performed the action
 * @param entityType The type of entity: "haplogroup" or "variant"
 * @param entityId   The ID of the affected entity
 * @param action     The action performed: "create", "update", or "delete"
 * @param oldValue   JSON representation of the entity before the change (for updates/deletes)
 * @param newValue   JSON representation of the entity after the change (for creates/updates)
 * @param comment    Optional comment explaining the change
 * @param createdAt  When the action was performed
 */
case class AuditLogEntry(
    id: Option[UUID] = None,
    userId: UUID,
    entityType: String,
    entityId: Int,
    action: String,
    oldValue: Option[JsValue],
    newValue: Option[JsValue],
    comment: Option[String],
    createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * Audit action types.
 */
enum AuditAction(val value: String) {
  case Create extends AuditAction("create")
  case Update extends AuditAction("update")
  case Delete extends AuditAction("delete")
}

object AuditAction {
  def fromString(s: String): Option[AuditAction] = s.toLowerCase match {
    case "create" => Some(Create)
    case "update" => Some(Update)
    case "delete" => Some(Delete)
    case _ => None
  }
}

/**
 * Entity types that can be audited.
 */
enum AuditEntityType(val value: String) {
  case Haplogroup extends AuditEntityType("haplogroup")
  case Variant extends AuditEntityType("variant")
}

object AuditEntityType {
  def fromString(s: String): Option[AuditEntityType] = s.toLowerCase match {
    case "haplogroup" => Some(Haplogroup)
    case "variant" => Some(Variant)
    case _ => None
  }
}
