package models.dal

import models.HaplogroupRelationship
import models.dal.domain.HaplogroupsTable
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime

/**
 * Represents the table definition for storing relationships between haplogroups in the database.
 * This table maps a child haplogroup to its parent haplogroup, maintaining lineage and hierarchy,
 * along with metadata about validity and source information.
 *
 * The table includes columns for a unique identifier, child and parent haplogroup IDs, revision
 * details, validity timeframes, and source of the relationship definition.
 *
 * Database schema details:
 * - Table name: "haplogroup_relationship"
 * - Primary key: "haplogroup_relationship_id"
 * - Foreign keys:
 *   - "child_haplogroup_fk" references HaplogroupsTable on "child_haplogroup_id"
 *   - "parent_haplogroup_fk" references HaplogroupsTable on "parent_haplogroup_id"
 *     - Indexes:
 *   - "unique_child_revision" ensures uniqueness of child haplogroup ID with the associated revision ID.
 *
 * Note:
 * - Relationships are associated with revision IDs, allowing tracking of updates or historical changes in the data.
 * - Validity is defined by "valid_from" and optionally "valid_until" columns, indicating the effective timespan of the relationship.
 */
class HaplogroupRelationshipsTable(tag: Tag) extends Table[HaplogroupRelationship](tag, "haplogroup_relationship") {
  def haplogroupRelationshipId = column[Int]("haplogroup_relationship_id", O.PrimaryKey, O.AutoInc)

  def childHaplogroupId = column[Int]("child_haplogroup_id")

  def parentHaplogroupId = column[Int]("parent_haplogroup_id")

  def revisionId = column[Int]("revision_id")

  def validFrom = column[LocalDateTime]("valid_from")

  def validUntil = column[Option[LocalDateTime]]("valid_until")

  def source = column[String]("source")

  def * = (haplogroupRelationshipId.?, childHaplogroupId, parentHaplogroupId, revisionId, validFrom, validUntil, source).mapTo[HaplogroupRelationship]

  def childHaplogroupFK = foreignKey("child_haplogroup_fk", childHaplogroupId, TableQuery[HaplogroupsTable])(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)

  def parentHaplogroupFK = foreignKey("parent_haplogroup_fk", parentHaplogroupId, TableQuery[HaplogroupsTable])(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)

  def uniqueChildRevision = index("unique_child_revision", (childHaplogroupId, revisionId), unique = true)
}
