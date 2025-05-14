package models.dal

import java.time.LocalDateTime
import models.Haplogroup
import slick.jdbc.PostgresProfile.api._

class HaplogroupsTable(tag: Tag) extends Table[Haplogroup](tag, "haplogroup") {
  def haplogroupId = column[Int]("haplogroup_id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name", O.Unique)
  def lineage = column[Option[String]]("lineage")
  def description = column[Option[String]]("description")
  def haplogroupType = column[String]("haplogroup_type")
  def revisionId = column[Int]("revision_id")
  def source = column[String]("source")
  def confidenceLevel = column[String]("confidence_level")
  def validFrom = column[LocalDateTime]("valid_from")
  def validUntil = column[Option[LocalDateTime]]("valid_until")

  def * = (haplogroupId.?, name, lineage, description, haplogroupType, revisionId, source, confidenceLevel, validFrom, validUntil).mapTo[Haplogroup]
}
