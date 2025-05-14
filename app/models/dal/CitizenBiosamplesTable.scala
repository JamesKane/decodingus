package models.dal

import com.vividsolutions.jts.geom.Point
import models.CitizenBiosample
import models.dal.MyPostgresProfile.api.*

import java.time.LocalDate
import java.util.UUID

class CitizenBiosamplesTable(tag: Tag) extends Table[CitizenBiosample](tag, "citizen_biosample") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def citizenBiosampleDid = column[String]("citizen_biosample_did", O.Unique)
  def sourcePlatform = column[Option[String]]("source_platform")
  def collectionDate = column[Option[LocalDate]]("collection_date")
  def geocoord = column[Option[Point]]("geocoord")
  def description = column[Option[String]]("description")
  def sampleGuid = column[UUID]("sample_guid")
  
  def * = (id.?, citizenBiosampleDid, sourcePlatform, collectionDate, geocoord, description, sampleGuid).mapTo[CitizenBiosample]
}
