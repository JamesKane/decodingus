package models.domain.support

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents a contact message from either an authenticated user or anonymous visitor.
 *
 * @param id            UUID Primary Key
 * @param userId        Optional - linked user if authenticated
 * @param senderName    Name provided by anonymous users
 * @param senderEmail   Email provided by anonymous users
 * @param subject       Message subject
 * @param message       Message content
 * @param status        Message status: new, read, replied, closed
 * @param ipAddressHash     Hashed IP address for spam tracking
 * @param userAgent         Browser user agent
 * @param createdAt         When the message was sent
 * @param updatedAt         Last update timestamp
 * @param userLastViewedAt  When the user last viewed this message thread
 */
case class ContactMessage(
    id: Option[UUID],
    userId: Option[UUID],
    senderName: Option[String],
    senderEmail: Option[String],
    subject: String,
    message: String,
    status: MessageStatus,
    ipAddressHash: Option[String],
    userAgent: Option[String],
    createdAt: LocalDateTime,
    updatedAt: LocalDateTime,
    userLastViewedAt: Option[LocalDateTime] = None
) {
  /**
   * Returns the display name for the sender.
   */
  def displayName: String = senderName.getOrElse("Authenticated User")

  /**
   * Returns true if the message is from an anonymous user.
   */
  def isAnonymous: Boolean = userId.isEmpty
}

/**
 * Status of a contact message.
 */
enum MessageStatus(val value: String) {
  case New extends MessageStatus("new")
  case Read extends MessageStatus("read")
  case Replied extends MessageStatus("replied")
  case Closed extends MessageStatus("closed")
}

object MessageStatus {
  def fromString(s: String): Option[MessageStatus] = s.toLowerCase match {
    case "new" => Some(New)
    case "read" => Some(Read)
    case "replied" => Some(Replied)
    case "closed" => Some(Closed)
    case _ => None
  }
}
