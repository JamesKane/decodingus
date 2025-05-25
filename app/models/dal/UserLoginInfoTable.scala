package models.dal

import models.UserLoginInfo
import models.User // Needed for foreign key
import models.dal.MyPostgresProfile.api.*
import java.time.ZonedDateTime
import java.util.UUID
import slick.lifted.ProvenShape

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