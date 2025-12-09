package models.dal.auth

import models.dal.MyPostgresProfile.api.*
import models.dal.domain.user.UsersTable
import models.domain.user.UserPdsInfo
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

/**
 * DAL table for auth.user_pds_info - stores the home PDS URL for each user's AT Protocol identity.
 */
class UserPdsInfoTable(tag: Tag) extends Table[UserPdsInfo](tag, Some("auth"), "user_pds_info") {
  def id = column[UUID]("id", O.PrimaryKey)

  def userId = column[UUID]("user_id", O.Unique)

  def pdsUrl = column[String]("pds_url")

  def did = column[String]("did", O.Unique)

  def handle = column[Option[String]]("handle")

  def createdAt = column[LocalDateTime]("created_at")

  def updatedAt = column[LocalDateTime]("updated_at")

  def * : ProvenShape[UserPdsInfo] = (
    id.?,
    userId,
    pdsUrl,
    did,
    handle,
    createdAt,
    updatedAt
  ).mapTo[UserPdsInfo]

  // Foreign key definition to users.id
  def userFk = foreignKey("fk_auth_user_pds_info_user_id", userId, TableQuery[UsersTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}
