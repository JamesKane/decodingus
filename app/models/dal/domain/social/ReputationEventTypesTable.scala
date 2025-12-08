package models.dal.domain.social

import models.domain.social.ReputationEventType
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

class ReputationEventTypesTable(tag: Tag) extends Table[ReputationEventType](tag, "reputation_event_types") {
  def id = column[UUID]("id", O.PrimaryKey)

  def name = column[String]("name", O.Unique)

  def description = column[Option[String]]("description")

  def defaultPointsChange = column[Int]("default_points_change")

  def isPositive = column[Boolean]("is_positive")

  def isSystemGenerated = column[Boolean]("is_system_generated")

    def createdAt = column[LocalDateTime]("created_at")

    def updatedAt = column[LocalDateTime]("updated_at")

  // Projection for the case class
  def * : ProvenShape[ReputationEventType] = (
    id.?,
    name,
    description,
    defaultPointsChange,
    isPositive,
    isSystemGenerated,
    createdAt,
    updatedAt
  ).mapTo[ReputationEventType]
}