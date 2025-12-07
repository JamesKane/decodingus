package models.domain.social

import java.time.LocalDateTime
import java.util.UUID

case class ReputationEventType(
                                id: Option[UUID] = None,
                                name: String,
                                description: Option[String] = None,
                                defaultPointsChange: Int,
                                isPositive: Boolean,
                                isSystemGenerated: Boolean,
                                createdAt: LocalDateTime = LocalDateTime.now(),
                                updatedAt: LocalDateTime = LocalDateTime.now()
                              )