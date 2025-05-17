package models.dal

import com.vividsolutions.jts.geom.Point
import models.Biosample
import models.dal.MyPostgresProfile.api.*

import java.util.UUID

/**
 * Represents the database table definition for storing biosample records.
 *
 * @constructor Initializes a new instance of the `BiosamplesTable` class, mapping columns 
 *              to the corresponding attributes of a `Biosample` entity.
 * @param tag A Slick `Tag` object used to scope and reference the table within a database schema.
 *
 *            Columns:
 *  - `id`: Unique identifier for the biosample (primary key, auto-increment).
 *  - `sampleAccession`: Unique accession string for the sample.
 *  - `description`: A detailed description of the biosample.
 *  - `alias`: An optional alias or alternative name for the sample.
 *  - `centerName`: Name of the center or institution responsible for the sample.
 *  - `sex`: An optional descriptor of the sample's sex.
 *  - `geocoord`: An optional geographic coordinate indicating the sample's origin.
 *  - `specimenDonorId`: Optional ID of the donor or specimen source for the sample.
 *  - `sampleGuid`: A UUID uniquely identifying the sample.
 *
 * Primary key:
 *  - `id`: The primary key for the table, auto-generated.
 *
 * Mapping:
 *  - Defines a mapping to the `Biosample` case class.
 */
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