package models.domain.user

import java.time.ZonedDateTime
import java.util.UUID

case class UserReputationScore(
                                userId: UUID,           // UUID Primary Key, Foreign Key to users.id
                                score: Long,            // BIGINT, Not Null, Default 0
                                lastCalculatedAt: ZonedDateTime // TIMESTAMP, Not Null, Default NOW()
                              )