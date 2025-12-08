package models.dal.domain.social

import models.domain.social.Message
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

class MessagesTable(tag: Tag) extends Table[Message](tag, Some("social"), "messages") {

  def id = column[UUID]("id", O.PrimaryKey)
  def conversationId = column[UUID]("conversation_id")
  def senderDid = column[String]("sender_did")
  def content = column[String]("content")
  def contentType = column[String]("content_type")
  def createdAt = column[LocalDateTime]("created_at")
  def isEdited = column[Boolean]("is_edited")

  def * : ProvenShape[Message] = (id, conversationId, senderDid, content, contentType, createdAt, isEdited).mapTo[Message]

  def conversationFk = foreignKey("fk_messages_conversation_id", conversationId, TableQuery[ConversationsTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}
