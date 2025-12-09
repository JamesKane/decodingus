package models.dal.support

import models.dal.MyPostgresProfile.api.*
import models.domain.support.{ContactMessage, MessageStatus}
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

/**
 * DAL table for support.contact_messages
 */
class ContactMessagesTable(tag: Tag) extends Table[ContactMessage](tag, Some("support"), "contact_messages") {

  // Custom mapper for MessageStatus enum
  implicit val messageStatusMapper: BaseColumnType[MessageStatus] =
    MappedColumnType.base[MessageStatus, String](
      status => status.value,
      str => MessageStatus.fromString(str).getOrElse(MessageStatus.New)
    )

  def id = column[UUID]("id", O.PrimaryKey)
  def userId = column[Option[UUID]]("user_id")
  def senderName = column[Option[String]]("sender_name")
  def senderEmail = column[Option[String]]("sender_email")
  def subject = column[String]("subject")
  def message = column[String]("message")
  def status = column[MessageStatus]("status")
  def ipAddressHash = column[Option[String]]("ip_address_hash")
  def userAgent = column[Option[String]]("user_agent")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")
  def userLastViewedAt = column[Option[LocalDateTime]]("user_last_viewed_at")

  def * : ProvenShape[ContactMessage] = (
    id.?,
    userId,
    senderName,
    senderEmail,
    subject,
    message,
    status,
    ipAddressHash,
    userAgent,
    createdAt,
    updatedAt,
    userLastViewedAt
  ).mapTo[ContactMessage]
}
