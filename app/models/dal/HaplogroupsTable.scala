package models.dal

import models.dal.MyPostgresProfile.api.*
import models.{Haplogroup, HaplogroupType}
import slick.ast.TypedType
import slick.lifted.{MappedProjection, ProvenShape}

import java.time.LocalDateTime

/**
 * Represents the Slick mapping for the `haplogroup` table in the database. Each row in this table corresponds
 * to a `Haplogroup` entity. The table captures information about genetic haplogroups, which are groups of populations
 * that share a common ancestor through either paternal (Y-DNA) or maternal (mtDNA) lineage.
 *
 * @constructor Creates a new instance of the HaplogroupsTable.
 * @param tag A Slick `Tag` object that contains meta-information used internally by Slick for query construction.
 *
 *            Table columns:
 *            - `haplogroupId`: Auto-incrementing primary key for the haplogroup (integer).
 *            - `name`: The name of the haplogroup (string).
 *            - `lineage`: Optional lineage description of the haplogroup (string).
 *            - `description`: Optional textual description of the haplogroup (string).
 *            - `haplogroupType`: The type of haplogroup (e.g., Y-DNA or mtDNA) stored as a string.
 *            - `revisionId`: An integer indicating the revision or version of the haplogroup data.
 *            - `source`: The data source or origin of the haplogroup information (string).
 *            - `confidenceLevel`: A string representing the confidence level of the haplogroup assignment.
 *            - `validFrom`: The timestamp indicating when this haplogroup record becomes valid.
 *            - `validUntil`: Optional timestamp indicating when this haplogroup record becomes invalid.
 *
 *            Relationship:
 *            This table contains all relevant metadata and structural information for managing haplogroups within an application or database.
 *
 *            Slick mapping:
 *            The `*` projection maps table rows to the `Haplogroup` case class.
 */
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
