package models.dal.domain.social

import models.domain.social.FeedPost
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.LocalDateTime
import java.util.UUID

class FeedPostsTable(tag: Tag) extends Table[FeedPost](tag, Some("social"), "feed_posts") {

  def id = column[UUID]("id", O.PrimaryKey)
  def authorDid = column[String]("author_did")
  def content = column[String]("content")
  def parentPostId = column[Option[UUID]]("parent_post_id")
  def rootPostId = column[Option[UUID]]("root_post_id")
  def topic = column[Option[String]]("topic")
  def authorReputationScore = column[Int]("author_reputation_score")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  def * : ProvenShape[FeedPost] = (id, authorDid, content, parentPostId, rootPostId, topic, authorReputationScore, createdAt, updatedAt).mapTo[FeedPost]

  def parentPostFk = foreignKey("fk_feed_posts_parent_id", parentPostId, TableQuery[FeedPostsTable])(_.id.?, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
  def rootPostFk = foreignKey("fk_feed_posts_root_id", rootPostId, TableQuery[FeedPostsTable])(_.id.?, onUpdate = ForeignKeyAction.Restrict, onDelete = ForeignKeyAction.Cascade)
}
