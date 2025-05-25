package models

import java.time.ZonedDateTime
import java.util.UUID

case class ReputationEvent(
                            id: Option[UUID],       // UUID Primary Key, default gen_random_uuid()
                            userId: UUID,           // UUID, Not Null, Foreign Key to users.id
                            eventTypeId: UUID,      // UUID, Not Null, Foreign Key to reputation_event_types.id
                            actualPointsChange: Int, // INTEGER, Not Null
                            sourceUserId: Option[UUID], // UUID, Nullable, Foreign Key to users.id (self-referencing)
                            relatedEntityType: Option[String], // VARCHAR(50), Nullable
                            relatedEntityId: Option[UUID], // UUID, Nullable, For specific post/comment/etc.
                            notes: Option[String],  // TEXT, Nullable
                            createdAt: ZonedDateTime  // TIMESTAMP, Not Null, Default NOW()
                          )