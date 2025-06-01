package models.dal.domain.genomics

import com.vividsolutions.jts.geom.Point
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{Biosample, BiosampleType}

import java.util.UUID

/**
 * Defines the Slick table mapping for the `Biosample` entity. This table represents 
 * biological samples with detailed metadata, including sample type, accession, 
 * description, and optional attributes such as donor information, geographical 
 * origin, and associated platforms.
 *
 * @param tag The Slick table tag used for binding the table to the database schema.
 */
class BiosamplesTable(tag: Tag) extends Table[Biosample](tag, "biosample") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def sampleType = column[BiosampleType]("sample_type")
  def sampleAccession = column[String]("sample_accession", O.Unique)
  def description = column[String]("description")
  def alias = column[Option[String]]("alias")
  def centerName = column[String]("center_name")
  def sex = column[Option[String]]("sex")
  def geocoord = column[Option[Point]]("geocoord")
  def specimenDonorId = column[Option[Int]]("specimen_donor_id")
  def sampleGuid = column[UUID]("sample_guid")
  def locked = column[Boolean]("locked", O.Default(false))
  def pgpParticipantId = column[Option[String]]("pgp_participant_id")
  def citizenBiosampleDid = column[Option[String]]("citizen_biosample_did")
  def sourcePlatform = column[Option[String]]("source_platform")
  def dateRangeStart = column[Option[Int]]("date_range_start")
  def dateRangeEnd = column[Option[Int]]("date_range_end")

  def * = (
    id.?,
    sampleType,
    sampleGuid,
    sampleAccession,
    description,
    alias,
    centerName,
    sex,
    geocoord,
    specimenDonorId,
    locked,
    pgpParticipantId,
    citizenBiosampleDid,
    sourcePlatform,
    dateRangeStart,
    dateRangeEnd
  ).mapTo[Biosample]
}
