package models.domain.user

import java.time.ZonedDateTime
import java.util.UUID

case class ReputationEventType(
                                id: Option[UUID], // UUID Primary Key, default gen_random_uuid()
                                name: String, // VARCHAR(100), Unique, Not Null
                                description: Option[String], // TEXT, Nullable
                                defaultPointsChange: Int, // INTEGER, Not Null
                                isPositive: Boolean, // BOOLEAN, Not Null
                                isSystemGenerated: Boolean, // BOOLEAN, Not Null, Default FALSE
                                createdAt: ZonedDateTime, // TIMESTAMP, Not Null, Default NOW()
                                updatedAt: ZonedDateTime // TIMESTAMP, Not Null, Default NOW()
                              )