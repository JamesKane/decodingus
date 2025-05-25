package models.dal.auth

import models.auth.Role
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.ZonedDateTime
import java.util.UUID

class RolesTable(tag: Tag) extends Table[Role](tag, Some("auth"), "roles") {
  def id = column[UUID]("id", O.PrimaryKey)

  def name = column[String]("name", O.Unique)

  def description = column[Option[String]]("description")

  def createdAt = column[ZonedDateTime]("created_at")

  def updatedAt = column[ZonedDateTime]("updated_at")

  def * : ProvenShape[Role] = (
    id.?,
    name,
    description,
    createdAt,
    updatedAt
  ).mapTo[Role]
}