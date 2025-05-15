package models.dal

import models.SpecimenDonor
import slick.jdbc.PostgresProfile.api._

class SpecimenDonorsTable(tag: Tag) extends Table[SpecimenDonor](tag, "specimen_donor") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def donorIdentifier = column[String]("donor_identifier")
  def originBiobank = column[String]("origin_biobank")

  def * = (id.?, donorIdentifier, originBiobank).mapTo[SpecimenDonor]
}
