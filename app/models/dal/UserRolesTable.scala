package models.dal

import models.{Role, User, UserRole}
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.util.UUID


class UserRolesTable(tag: Tag) extends Table[UserRole](tag, Some("auth"), "user_roles") {
  def userId = column[UUID]("user_id")

  def roleId = column[UUID]("role_id")


  override def * : ProvenShape[UserRole] = (userId, roleId).mapTo[UserRole]

  def pk = primaryKey("pk_auth_user_roles", (userId, roleId))

  def userFk = foreignKey("fk_auth_user_roles_user_id", userId, TableQuery[UsersTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)

  def roleFk = foreignKey("fk_auth_user_roles_role_id", roleId, TableQuery[RolesTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}