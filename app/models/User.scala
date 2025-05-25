package models

import java.time.ZonedDateTime
import java.util.UUID

case class User(
                 id: Option[UUID],
                 email: Option[String],
                 did: String,
                 handle: Option[String],
                 displayName: Option[String],
                 createdAt: ZonedDateTime,
                 updatedAt: ZonedDateTime,
                 isActive: Boolean
               )