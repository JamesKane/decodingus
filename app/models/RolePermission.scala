package models

import java.util.UUID

case class RolePermission(
                           roleId: UUID,
                           permissionId: UUID
                         )