package models.dal

import models.{Permission, Role, RolePermission}
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.util.UUID


class RolePermissionsTable(tag: Tag) extends Table[RolePermission](tag, Some("auth"), "role_permissions") {
  def roleId = column[UUID]("role_id")

  def permissionId = column[UUID]("permission_id")


  override def * : ProvenShape[RolePermission] = (roleId, permissionId).mapTo[RolePermission]

  def pk = primaryKey("pk_auth_role_permissions", (roleId, permissionId))


  def roleFk = foreignKey("fk_auth_role_permissions_role_id", roleId, TableQuery[RolesTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)


  def permissionFk = foreignKey("fk_auth_role_permissions_permission_id", permissionId, TableQuery[PermissionsTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}