package models.domain.user

import java.time.ZonedDateTime
import java.util.UUID

case class UserPdsInfo(
                        id: Option[UUID],       // UUID Primary Key, default gen_random_uuid()
                        userId: UUID,           // UUID, Unique, Not Null, Foreign Key to users.id
                        pdsUrl: String,         // VARCHAR(255), Not Null
                        did: String,            // VARCHAR(255), Unique, Not Null, Foreign Key to users.did
                        createdAt: ZonedDateTime, // TIMESTAMP, Not Null, Default NOW()
                        updatedAt: ZonedDateTime  // TIMESTAMP, Not Null, Default NOW()
                      )