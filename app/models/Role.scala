package models

import java.time.ZonedDateTime
import java.util.UUID

case class Role(
                 id: Option[UUID],
                 name: String,
                 description: Option[String],
                 createdAt: ZonedDateTime,
                 updatedAt: ZonedDateTime
               )
