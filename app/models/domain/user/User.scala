package models.domain.user

import java.time.LocalDateTime
import java.util.UUID

case class User(
                 id: Option[UUID],
                 email: Option[String],
                 did: String,
                 handle: Option[String],
                 displayName: Option[String],
                 createdAt: LocalDateTime,
                 updatedAt: LocalDateTime,
                 isActive: Boolean
               )