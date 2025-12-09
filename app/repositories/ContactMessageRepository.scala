package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.support.{ContactMessage, MessageReply, MessageStatus}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ContactMessageRepository @Inject()(
    protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  private val contactMessages = DatabaseSchema.support.contactMessages
  private val messageReplies = DatabaseSchema.support.messageReplies

  // Import the MessageStatus mapper from the table
  import models.dal.support.ContactMessagesTable
  private val tableForMapper = new ContactMessagesTable(null)
  implicit val messageStatusMapper: BaseColumnType[MessageStatus] = tableForMapper.messageStatusMapper

  // ===== Contact Messages =====

  def create(message: ContactMessage): Future[ContactMessage] = {
    val messageWithId = message.copy(id = Some(message.id.getOrElse(UUID.randomUUID())))
    db.run((contactMessages returning contactMessages) += messageWithId)
  }

  def findById(id: UUID): Future[Option[ContactMessage]] = {
    db.run(contactMessages.filter(_.id === id).result.headOption)
  }

  def findByUserId(userId: UUID): Future[Seq[ContactMessage]] = {
    db.run(
      contactMessages
        .filter(_.userId === userId)
        .sortBy(_.createdAt.desc)
        .result
    )
  }

  def findAll(status: Option[MessageStatus] = None, limit: Int = 50, offset: Int = 0): Future[Seq[ContactMessage]] = {
    val baseQuery = contactMessages.sortBy(_.createdAt.desc)
    val filteredQuery = status match {
      case Some(s) => baseQuery.filter(_.status === s)
      case None => baseQuery
    }
    db.run(filteredQuery.drop(offset).take(limit).result)
  }

  def countByStatus(status: Option[MessageStatus] = None): Future[Int] = {
    val baseQuery = contactMessages
    val filteredQuery = status match {
      case Some(s) => baseQuery.filter(_.status === s)
      case None => baseQuery
    }
    db.run(filteredQuery.length.result)
  }

  def updateStatus(id: UUID, status: MessageStatus): Future[Int] = {
    db.run(
      contactMessages
        .filter(_.id === id)
        .map(m => (m.status, m.updatedAt))
        .update((status, LocalDateTime.now()))
    )
  }

  // ===== Message Replies =====

  def createReply(reply: MessageReply): Future[MessageReply] = {
    val replyWithId = reply.copy(id = Some(reply.id.getOrElse(UUID.randomUUID())))
    db.run((messageReplies returning messageReplies) += replyWithId)
  }

  def findRepliesByMessageId(messageId: UUID): Future[Seq[MessageReply]] = {
    db.run(
      messageReplies
        .filter(_.messageId === messageId)
        .sortBy(_.createdAt.asc)
        .result
    )
  }

  def markEmailSent(replyId: UUID): Future[Int] = {
    db.run(
      messageReplies
        .filter(_.id === replyId)
        .map(r => (r.emailSent, r.emailSentAt))
        .update((true, Some(LocalDateTime.now())))
    )
  }

  /**
   * Gets a message with all its replies.
   */
  def findWithReplies(messageId: UUID): Future[Option[(ContactMessage, Seq[MessageReply])]] = {
    for {
      messageOpt <- findById(messageId)
      replies <- findRepliesByMessageId(messageId)
    } yield messageOpt.map(m => (m, replies))
  }

  /**
   * Update the user's last viewed timestamp for a message.
   */
  def updateUserLastViewed(messageId: UUID): Future[Int] = {
    db.run(
      contactMessages
        .filter(_.id === messageId)
        .map(_.userLastViewedAt)
        .update(Some(LocalDateTime.now()))
    )
  }

  /**
   * Update the user's last viewed timestamp for all their messages.
   */
  def updateUserLastViewedAll(userId: UUID): Future[Int] = {
    db.run(
      contactMessages
        .filter(_.userId === userId)
        .map(_.userLastViewedAt)
        .update(Some(LocalDateTime.now()))
    )
  }

  // ===== Badge Count Methods =====

  /**
   * Count new/unread messages for admin badge.
   * Returns count of messages with status 'new' or 'read' (not yet replied/closed).
   */
  def countUnreadForAdmin: Future[Int] = {
    db.run(
      contactMessages
        .filter(m => m.status === MessageStatus.New || m.status === MessageStatus.Read)
        .length
        .result
    )
  }

  /**
   * Count messages with unread replies for a specific user.
   * A message has unread replies if:
   * - It has at least one reply
   * - The latest reply was created after userLastViewedAt (or userLastViewedAt is null)
   */
  def countUnreadRepliesForUser(userId: UUID): Future[Int] = {
    // Use Slick query with subquery for this logic
    val messagesWithNewReplies = for {
      msg <- contactMessages if msg.userId === userId
      reply <- messageReplies if reply.messageId === msg.id
      if msg.userLastViewedAt.isEmpty || reply.createdAt > msg.userLastViewedAt
    } yield msg.id

    db.run(messagesWithNewReplies.distinct.length.result)
  }
}
