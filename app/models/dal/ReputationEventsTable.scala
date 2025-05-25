package models.dal

import models.{ReputationEvent, ReputationEventType, User}
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.ZonedDateTime
import java.util.UUID

class ReputationEventsTable(tag: Tag) extends Table[ReputationEvent](tag, "reputation_events") {
  def id = column[UUID]("id", O.PrimaryKey)

  def userId = column[UUID]("user_id")

  def eventTypeId = column[UUID]("event_type_id")

  def actualPointsChange = column[Int]("actual_points_change")

  def sourceUserId = column[Option[UUID]]("source_user_id")

  def relatedEntityType = column[Option[String]]("related_entity_type")

  def relatedEntityId = column[Option[UUID]]("related_entity_id")

  def notes = column[Option[String]]("notes")

  def createdAt = column[ZonedDateTime]("created_at")

  // Projection for the case class
  def * : ProvenShape[ReputationEvent] = (
    id.?,
    userId,
    eventTypeId,
    actualPointsChange,
    sourceUserId,
    relatedEntityType,
    relatedEntityId,
    notes,
    createdAt
  ).mapTo[ReputationEvent]

  def userFk = foreignKey("fk_reputation_events_user_id", userId, TableQuery[UsersTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)

  def eventTypeFk = foreignKey("fk_reputation_events_event_type_id", eventTypeId, TableQuery[ReputationEventTypesTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Restrict)

  def sourceUserFk = foreignKey("fk_reputation_events_source_user_id", sourceUserId, TableQuery[UsersTable])(_.id.?, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.SetNull)

}