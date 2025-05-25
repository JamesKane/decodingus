package models.dal

import models.{UserLoginInfo, UserOauth2Info}
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.ZonedDateTime
import java.util.UUID


class UserOauth2InfoTable(tag: Tag) extends Table[UserOauth2Info](tag, Some("auth"), "user_oauth2_info") {
  def id = column[UUID]("id", O.PrimaryKey)

  // Foreign key to auth.user_login_info (id)
  def loginInfoId = column[UUID]("login_info_id", O.Unique)

  def accessToken = column[String]("access_token")

  def tokenType = column[Option[String]]("token_type")

  def expiresIn = column[Option[Long]]("expires_in")

  def refreshToken = column[Option[String]]("refresh_token")

  def createdAt = column[ZonedDateTime]("created_at")

  def updatedAt = column[ZonedDateTime]("updated_at")

  def scope = column[Option[String]]("scope")

  // Projection for the case class
  def * : ProvenShape[UserOauth2Info] = (
    id.?,
    loginInfoId,
    accessToken,
    tokenType,
    expiresIn,
    refreshToken,
    createdAt,
    updatedAt,
    scope
  ).mapTo[UserOauth2Info]


  def loginInfoFk = foreignKey("fk_auth_user_oauth2_info_login_info_id", loginInfoId, TableQuery[UserLoginInfoTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}