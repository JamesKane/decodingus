package models.dal

import models.UserPdsInfo
import models.User
import models.dal.MyPostgresProfile.api.*
import java.time.ZonedDateTime
import java.util.UUID
import slick.lifted.ProvenShape

class UserPdsInfoTable(tag: Tag) extends Table[UserPdsInfo](tag, "user_pds_info") {
  def id = column[UUID]("id", O.PrimaryKey)

  def userId = column[UUID]("user_id", O.Unique)

  def pdsUrl = column[String]("pds_url")

  // Foreign key to public.users (did)
  def did = column[String]("did", O.Unique)

  def createdAt = column[ZonedDateTime]("created_at")
  def updatedAt = column[ZonedDateTime]("updated_at")

  // Projection for the case class
  def * : ProvenShape[UserPdsInfo] = (
    id.?,
    userId,
    pdsUrl,
    did,
    createdAt,
    updatedAt
  ).mapTo[UserPdsInfo]

  // Foreign key definition to users.id
  def userFkId = foreignKey("fk_user_pds_info_user_id", userId, TableQuery[UsersTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)

  // Foreign key definition to users.did
  // Note: Referencing a non-PK unique column is supported by Slick, but the target table must have a unique index on 'did'.
  // We assume UsersTable has a 'def did' column with O.Unique (which we defined in the previous UserTable).
  def userFkDid = foreignKey("fk_user_pds_info_did", did, TableQuery[UsersTable])(_.did, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}