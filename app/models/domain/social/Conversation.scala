package models.domain.social

import java.time.LocalDateTime
import java.util.UUID

case class Conversation(
                         id: UUID = UUID.randomUUID(),
                         `type`: String, // 'DIRECT', 'GROUP', 'SYSTEM', 'RECRUITMENT'
                         createdAt: LocalDateTime = LocalDateTime.now(),
                         updatedAt: LocalDateTime = LocalDateTime.now()
                       )
