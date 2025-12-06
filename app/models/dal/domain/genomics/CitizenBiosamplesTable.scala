package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{BiologicalSex, CitizenBiosample}
import com.vividsolutions.jts.geom.Point
import java.time.{LocalDate, LocalDateTime}
import java.util.UUID

class CitizenBiosamplesTable(tag: Tag) extends Table[CitizenBiosample](tag, "citizen_biosample") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def citizenBiosampleDid = column[Option[String]]("citizen_biosample_did", O.Unique)
  def accession = column[Option[String]]("accession")
  def alias = column[Option[String]]("alias")
  def sourcePlatform = column[Option[String]]("source_platform")
  def collectionDate = column[Option[LocalDate]]("collection_date")
  def sex = column[Option[BiologicalSex]]("sex")
  def geocoord = column[Option[Point]]("geocoord")
  def description = column[Option[String]]("description")
  def sampleGuid = column[UUID]("sample_guid")
  
  def deleted = column[Boolean]("deleted", O.Default(false))
  def atCid = column[Option[String]]("at_cid")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  def * = (
    id.?,
    citizenBiosampleDid,
    accession,
    alias,
    sourcePlatform,
    collectionDate,
    sex,
    geocoord,
    description,
    sampleGuid,
    deleted,
    atCid,
    createdAt,
    updatedAt
  ).mapTo[CitizenBiosample]
}
