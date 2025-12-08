package models.dal.domain.social

import models.domain.social.ConversationParticipant
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

class ConversationParticipantsTable(tag: Tag) extends Table[ConversationParticipant](tag, Some("social"), "conversation_participants") {

  def conversationId = column[UUID]("conversation_id")
  def userDid = column[String]("user_did")
  def role = column[String]("role")
  def lastReadAt = column[Option[LocalDateTime]]("last_read_at")
  def joinedAt = column[LocalDateTime]("joined_at")

  def * : ProvenShape[ConversationParticipant] = (conversationId, userDid, role, lastReadAt, joinedAt).mapTo[ConversationParticipant]

  def pk = primaryKey("pk_conversation_participants", (conversationId, userDid))

  def conversationFk = foreignKey("fk_conversation_participants_conversation_id", conversationId, TableQuery[ConversationsTable])(_.id, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}
