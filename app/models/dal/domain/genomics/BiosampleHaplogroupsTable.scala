package models.dal.domain.genomics

import models.domain.BiosampleHaplogroup
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID

/**
 * Represents the mapping of biosamples to their respective haplogroups within the database.
 *
 * @constructor Creates a new instance of the `BiosampleHaplogroupsTable` mapping.
 * @param tag A Slick tag identifying this table mapping for queries.
 *
 *            The table `biosample_haplogroup` contains the following columns:
 *   - `sample_guid`: The primary key, representing the UUID that uniquely identifies a biosample.
 *   - `y_haplogroup_id`: An optional column that holds the identifier for the Y-haplogroup associated with the sample.
 *   - `mt_haplogroup_id`: An optional column that holds the identifier for the mitochondrial (MT) haplogroup associated with the sample.
 *
 * The mapping between columns and fields in the `BiosampleHaplogroup` case class is defined here.
 */
class BiosampleHaplogroupsTable(tag: Tag) extends Table[BiosampleHaplogroup](tag, "biosample_haplogroup") {
  def sampleGuid = column[UUID]("sample_guid", O.PrimaryKey)

  def yHaplogroupId = column[Option[Int]]("y_haplogroup_id")

  def mtHaplogroupId = column[Option[Int]]("mt_haplogroup_id")

  def * = (sampleGuid, yHaplogroupId, mtHaplogroupId).mapTo[BiosampleHaplogroup]
}