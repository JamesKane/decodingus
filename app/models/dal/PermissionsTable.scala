package models.dal

import models.Permission
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.ZonedDateTime
import java.util.UUID

class PermissionsTable(tag: Tag) extends Table[Permission](tag, Some("auth"), "permissions") {
  def id = column[UUID]("id", O.PrimaryKey)

  def name = column[String]("name", O.Unique)

  def description = column[Option[String]]("description")

  def createdAt = column[ZonedDateTime]("created_at")

  def updatedAt = column[ZonedDateTime]("updated_at")


  def * : ProvenShape[Permission] = (
    id.?,
    name,
    description,
    createdAt,
    updatedAt
  ).mapTo[Permission]
}