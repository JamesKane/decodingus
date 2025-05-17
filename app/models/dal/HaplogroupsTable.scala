package models.dal

import models.dal.MyPostgresProfile.api.*
import models.{Haplogroup, HaplogroupType}
import slick.ast.TypedType
import slick.lifted.{MappedProjection, ProvenShape}

import java.time.LocalDateTime

class HaplogroupsTable(tag: Tag) extends Table[Haplogroup](tag, "haplogroup") {


  given TypedType[HaplogroupType] = MappedColumnType.base[HaplogroupType, String](
    _.toString,
    HaplogroupType.valueOf
  )

  def haplogroupId = column[Int]("haplogroup_id", O.PrimaryKey, O.AutoInc)

  def name = column[String]("name")

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
