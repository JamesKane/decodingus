package models.auth

import java.util.UUID

case class UserRole(
                     userId: UUID,
                     roleId: UUID
                   )