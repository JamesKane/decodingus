package models.dal.support

import models.dal.MyPostgresProfile.api.*
import models.domain.support.MessageReply
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

/**
 * DAL table for support.message_replies
 */
class MessageRepliesTable(tag: Tag) extends Table[MessageReply](tag, Some("support"), "message_replies") {

  def id = column[UUID]("id", O.PrimaryKey)
  def messageId = column[UUID]("message_id")
  def adminUserId = column[UUID]("admin_user_id")
  def replyText = column[String]("reply_text")
  def emailSent = column[Boolean]("email_sent")
  def emailSentAt = column[Option[LocalDateTime]]("email_sent_at")
  def createdAt = column[LocalDateTime]("created_at")

  def * : ProvenShape[MessageReply] = (
    id.?,
    messageId,
    adminUserId,
    replyText,
    emailSent,
    emailSentAt,
    createdAt
  ).mapTo[MessageReply]

  def messageFk = foreignKey("fk_message_replies_message_id", messageId, TableQuery[ContactMessagesTable])(_.id)
}
