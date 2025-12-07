package models.dal.domain.social

import models.domain.social.Conversation
import slick.jdbc.PostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

class ConversationsTable(tag: Tag) extends Table[Conversation](tag, Some("social"), "conversations") {

  def id = column[UUID]("id", O.PrimaryKey)
  def `type` = column[String]("type")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  def * : ProvenShape[Conversation] = (id, `type`, createdAt, updatedAt).mapTo[Conversation]
}
