package models.dal.domain.haplogroups

import models.domain.haplogroups.RelationshipRevisionMetadata
import models.dal.MyPostgresProfile.api.*

import java.time.LocalDateTime

/**
 * Represents the table definition for storing and managing metadata about
 * revisions made to haplogroup relationships. Each entry captures details
 * about a specific revision, including the author, timestamp, and a description
 * of the changes made. It also includes references to the affected haplogroup
 * relationship and optionally to the previous revision.
 *
 * Table schema details:
 * - Table name: `relationship_revision_metadata`
 * - Primary key: (`haplogroup_relationship_id`, `revision_id`)
 * - Foreign keys:
 *   - `haplogroup_relationship_id` references `HaplogroupRelationshipsTable(haplogroupRelationshipId)`
 *
 * Columns:
 * - `haplogroup_relationship_id`: The ID of the haplogroup relationship being revised.
 * - `revision_id`: A unique identifier for the specific revision.
 * - `author`: The name or identifier of the individual or entity that authored the revision.
 * - `timestamp`: The time at which the revision was created.
 * - `comment`: A descriptive comment explaining the details or purpose of the revision.
 * - `change_type`: A string indicating the type of change made (e.g., "update", "create", "delete").
 * - `previous_revision_id`: An optional reference to the ID of the previous revision, if applicable.
 *
 * This table is intended to provide traceability and context for changes made over time to
 * the haplogroup relationship data, supporting historical comparisons and auditing requirements.
 */
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

