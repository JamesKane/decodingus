package models.dal.curator

import models.dal.MyPostgresProfile.api.*
import models.domain.curator.AuditLogEntry
import play.api.libs.json.JsValue
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

/**
 * DAL table for curator.audit_log
 */
class AuditLogTable(tag: Tag) extends Table[AuditLogEntry](tag, Some("curator"), "audit_log") {

  def id = column[UUID]("id", O.PrimaryKey)
  def userId = column[UUID]("user_id")
  def entityType = column[String]("entity_type")
  def entityId = column[Int]("entity_id")
  def action = column[String]("action")
  def oldValue = column[Option[JsValue]]("old_value")
  def newValue = column[Option[JsValue]]("new_value")
  def comment = column[Option[String]]("comment")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[AuditLogEntry] = (
    id.?,
    userId,
    entityType,
    entityId,
    action,
    oldValue,
    newValue,
    comment,
    createdAt
  ).mapTo[AuditLogEntry]
}
