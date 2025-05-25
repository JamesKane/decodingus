package models.dal

import models.User
import models.dal.MyPostgresProfile.api.*
import java.time.ZonedDateTime
import java.util.UUID

class UsersTable(tag: Tag) extends Table[User](tag, "users") {
  def id = column[UUID]("id", O.PrimaryKey) // O.AutoInc is not used for UUID defaults

  def email = column[Option[String]]("email", O.Unique)

  def did = column[String]("did", O.Unique)

  def handle = column[Option[String]]("handle", O.Unique)

  def displayName = column[Option[String]]("display_name")

  def createdAt = column[ZonedDateTime]("created_at")

  def updatedAt = column[ZonedDateTime]("updated_at")

  def isActive = column[Boolean]("is_active")

  def * = (
    id.?,
    email,
    did,
    handle,
    displayName,
    createdAt,
    updatedAt,
    isActive
  ).mapTo[User]
}