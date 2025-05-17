package models.dal

import models.SpecimenDonor
import slick.jdbc.PostgresProfile.api.*

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

  def * = (id.?, donorIdentifier, originBiobank).mapTo[SpecimenDonor]
}
