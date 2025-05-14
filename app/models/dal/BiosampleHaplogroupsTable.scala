package models.dal

import models.BiosampleHaplogroup
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID

class BiosampleHaplogroupsTable(tag: Tag) extends Table[BiosampleHaplogroup](tag, "biosample_haplogroup") {
  def sampleGuid = column[UUID]("sample_guid", O.PrimaryKey)
  def yHaplogroupId = column[Option[Int]]("y_haplogroup_id")
  def mtHaplogroupId = column[Option[Int]]("mt_haplogroup_id")

  def * = (sampleGuid, yHaplogroupId, mtHaplogroupId).mapTo[BiosampleHaplogroup]
}