package models.dal

import java.time.LocalDateTime
import models.HaplogroupRelationship
import slick.jdbc.PostgresProfile.api._

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
