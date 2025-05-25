package models.auth

import java.util.UUID

case class RolePermission(
                           roleId: UUID,
                           permissionId: UUID
                         )