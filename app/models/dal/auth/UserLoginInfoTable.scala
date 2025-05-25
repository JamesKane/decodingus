package models.dal.auth

import models.User
import models.auth.UserLoginInfo
import models.dal.MyPostgresProfile.api.*
import models.dal.UsersTable
import slick.lifted.ProvenShape

import java.time.ZonedDateTime
import java.util.UUID

class UserLoginInfoTable(tag: Tag) extends Table[UserLoginInfo](tag, Some("auth"), "user_login_info") {
  def id = column[UUID]("id", O.PrimaryKey)

  def userId = column[UUID]("user_id")

  def providerId = column[String]("provider_id")

  def providerKey = column[String]("provider_key")

  def createdAt = column[ZonedDateTime]("created_at")

  def updatedAt = column[ZonedDateTime]("updated_at")

  def uniqueProvider = index("uq_auth_provider_id_key", (providerId, providerKey), unique = true)

  // Projection for the case class
  def * : ProvenShape[UserLoginInfo] = (
    id.?,
    userId,
    providerId,
    providerKey,
    createdAt,
    updatedAt
  ).mapTo[UserLoginInfo]

  def userFk = foreignKey("fk_auth_user_login_info_user_id", userId, TableQuery[UsersTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}