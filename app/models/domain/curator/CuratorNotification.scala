package models.domain.curator

import play.api.libs.json.{Format, Json, OFormat, Reads, Writes}
import java.time.LocalDateTime

/**
 * Type of curator notification.
 */
enum NotificationType:
  case ChangeSetReady      // Change set is ready for review
  case ChangeSetApplied    // Change set was applied to production
  case ChangeSetDiscarded  // Change set was discarded
  case AmbiguityAlert      // High-priority ambiguities detected
  case ReviewReminder      // Reminder to review pending changes

object NotificationType {
  def fromString(s: String): NotificationType = s.toUpperCase match {
    case "CHANGE_SET_READY" => NotificationType.ChangeSetReady
    case "CHANGE_SET_APPLIED" => NotificationType.ChangeSetApplied
    case "CHANGE_SET_DISCARDED" => NotificationType.ChangeSetDiscarded
    case "AMBIGUITY_ALERT" => NotificationType.AmbiguityAlert
    case "REVIEW_REMINDER" => NotificationType.ReviewReminder
    case other => throw new IllegalArgumentException(s"Unknown NotificationType: $other")
  }

  def toDbString(nt: NotificationType): String = nt match {
    case NotificationType.ChangeSetReady => "CHANGE_SET_READY"
    case NotificationType.ChangeSetApplied => "CHANGE_SET_APPLIED"
    case NotificationType.ChangeSetDiscarded => "CHANGE_SET_DISCARDED"
    case NotificationType.AmbiguityAlert => "AMBIGUITY_ALERT"
    case NotificationType.ReviewReminder => "REVIEW_REMINDER"
  }

  implicit val reads: Reads[NotificationType] = Reads.StringReads.map(fromString)
  implicit val writes: Writes[NotificationType] = Writes.StringWrites.contramap(toDbString)
  implicit val format: Format[NotificationType] = Format(reads, writes)
}

/**
 * A notification for curators about tree versioning events.
 *
 * @param id Unique identifier
 * @param notificationType Type of notification
 * @param title Short title
 * @param message Detailed message
 * @param changeSetId Related change set (if applicable)
 * @param createdAt When notification was created
 * @param readAt When notification was read (null if unread)
 * @param link Optional link to related resource
 */
case class CuratorNotification(
  id: Option[Int],
  notificationType: NotificationType,
  title: String,
  message: String,
  changeSetId: Option[Int] = None,
  createdAt: LocalDateTime = LocalDateTime.now(),
  readAt: Option[LocalDateTime] = None,
  link: Option[String] = None
) {
  def isRead: Boolean = readAt.isDefined
}

object CuratorNotification {
  implicit val format: OFormat[CuratorNotification] = Json.format[CuratorNotification]
}
