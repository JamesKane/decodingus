package models.domain.social

import java.time.LocalDateTime
import java.util.UUID

case class ReputationEvent(
                            id: Option[UUID] = None,
                            userId: UUID,
                            eventTypeId: UUID,
                            actualPointsChange: Int,
                            sourceUserId: Option[UUID] = None,
                            relatedEntityType: Option[String] = None,
                            relatedEntityId: Option[UUID] = None,
                            notes: Option[String] = None,
                            createdAt: LocalDateTime = LocalDateTime.now()
                          )