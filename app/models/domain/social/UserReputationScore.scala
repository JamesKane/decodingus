package models.domain.social

import java.time.ZonedDateTime
import java.time.LocalDateTime
import java.util.UUID

case class UserReputationScore(
                        userId: UUID,
                        score: Long,
                        lastCalculatedAt: LocalDateTime
                      )