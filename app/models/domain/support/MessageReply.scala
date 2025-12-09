package models.domain.support

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents an admin reply to a contact message.
 *
 * @param id          UUID Primary Key
 * @param messageId   Foreign key to the contact message
 * @param adminUserId The admin user who replied
 * @param replyText   Reply content
 * @param emailSent   Whether an email was sent (for anonymous users)
 * @param emailSentAt When the email was sent
 * @param createdAt   When the reply was created
 */
case class MessageReply(
    id: Option[UUID],
    messageId: UUID,
    adminUserId: UUID,
    replyText: String,
    emailSent: Boolean,
    emailSentAt: Option[LocalDateTime],
    createdAt: LocalDateTime
)
