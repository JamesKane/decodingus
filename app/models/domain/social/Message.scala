package models.domain.social

import java.time.LocalDateTime
import java.util.UUID

case class Message(
                    id: UUID = UUID.randomUUID(),
                    conversationId: UUID,
                    senderDid: String,
                    content: String,
                    contentType: String = "TEXT", // 'TEXT', 'MARKDOWN', 'JSON_PAYLOAD'
                    createdAt: LocalDateTime = LocalDateTime.now(),
                    isEdited: Boolean = false
                  )
