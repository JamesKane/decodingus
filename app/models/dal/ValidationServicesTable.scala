package models.dal

import models.ValidationService
import models.dal.MyPostgresProfile.api.*

import java.util.UUID

class ValidationServicesTable(tag: Tag) extends Table[ValidationService](tag, "validation_service") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def guid = column[UUID]("guid", O.Unique) // UUID, with unique constraint

  def name = column[String]("name", O.Unique) // String, with unique constraint

  def description = column[Option[String]]("description")

  def trustLevel = column[Option[String]]("trust_level")

  def * = (
    id.?,
    guid,
    name,
    description,
    trustLevel
  ).mapTo[ValidationService]
}