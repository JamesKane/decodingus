package models.dal

import com.vividsolutions.jts.geom.Point
import models.Biosample
import models.dal.MyPostgresProfile.api.*

import java.util.UUID

class BiosamplesTable(tag: Tag) extends Table[Biosample](tag, "biosample") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def sampleAccession = column[String]("sample_accession", O.Unique)
  def description = column[String]("description")
  def alias = column[Option[String]]("alias")
  def centerName = column[String]("center_name")
  def sex = column[Option[String]]("sex")
  def geocoord = column[Option[Point]]("geocoord")
  def specimenDonorId = column[Option[Int]]("specimen_donor_id")
  def sampleGuid = column[UUID]("sample_guid")

  def * = (id.?, sampleAccession, description, alias, centerName, sex, geocoord, specimenDonorId, sampleGuid).mapTo[Biosample]
}