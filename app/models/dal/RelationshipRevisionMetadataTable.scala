package models.dal

import models.RelationshipRevisionMetadata
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime

class RelationshipRevisionMetadataTable(tag: Tag) extends Table[RelationshipRevisionMetadata](tag, "relationship_revision_metadata") {
  def haplogroup_relationship_id = column[Int]("haplogroup_relationship_id")
  def revisionId = column[Int]("revision_id")
  def author = column[String]("author")
  def timestamp = column[LocalDateTime]("timestamp")
  def comment = column[String]("comment")
  def changeType = column[String]("change_type")
  def previousRevisionId = column[Option[Int]]("previous_revision_id")

  def pk = primaryKey("pk_relationship_revision_metadata", (haplogroup_relationship_id, revisionId))

  def relationshipFk = foreignKey(
    "fk_relationship",
    haplogroup_relationship_id,
    TableQuery[HaplogroupRelationshipsTable])(_.haplogroupRelationshipId)

  def * = (
    haplogroup_relationship_id,
    revisionId,
    author,
    timestamp,
    comment,
    changeType,
    previousRevisionId
  ).mapTo[RelationshipRevisionMetadata]
}

val relationshipRevisionMetadata = TableQuery[RelationshipRevisionMetadataTable]

