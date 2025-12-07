package models.domain.social

import java.time.LocalDateTime
import java.util.UUID

case class ConversationParticipant(
                                    conversationId: UUID,
                                    userDid: String,
                                    role: String = "MEMBER", // 'ADMIN', 'MEMBER'
                                    lastReadAt: Option[LocalDateTime] = None,
                                    joinedAt: LocalDateTime = LocalDateTime.now()
                                  )
