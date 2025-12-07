package models.dal.domain.genomics

import com.vividsolutions.jts.geom.Point
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{BiologicalSex, BiosampleType, SpecimenDonor}

/**
 * Represents the database table definition for storing specimen donor records.
 *
 * @constructor Initializes a new instance of the `SpecimenDonorsTable` class.
 * @param tag A Slick `Tag` object used to scope and reference the table within a database schema.
 *
 *            This table is linked to `SpecimenDonor` entity and is used to store information
 *            about donors, including their unique identifier and the biobank they originate from.
 *
 *            Columns:
 *  - `id`: Auto-incremented primary key, uniquely identifying each specimen donor.
 *  - `donorIdentifier`: A unique string identifier for the donor, used for cross-referencing.
 *  - `originBiobank`: The biobank's name or identifier where the donor originates.
 *
 * Primary key:
 *  - `id`: Serves as the primary key for the table.
 *
 * Mapping:
 *  - Defines a mapping to the `SpecimenDonor` case class.
 */
class SpecimenDonorsTable(tag: Tag) extends Table[SpecimenDonor](tag, "specimen_donor") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def donorIdentifier = column[String]("donor_identifier")

  def originBiobank = column[String]("origin_biobank")

  def donorType = column[BiosampleType]("donor_type")

  def sex = column[Option[BiologicalSex]]("sex")

  def geocoord = column[Option[Point]]("geocoord")

  def pgpParticipantId = column[Option[String]]("pgp_participant_id")

  def atUri = column[Option[String]]("at_uri")

  def dateRangeStart = column[Option[Int]]("date_range_start")

  def dateRangeEnd = column[Option[Int]]("date_range_end")

  def * = (
    id.?,
    donorIdentifier,
    originBiobank,
    donorType,
    sex,
    geocoord,
    pgpParticipantId,
    atUri,
    dateRangeStart,
    dateRangeEnd
  ).mapTo[SpecimenDonor]
}