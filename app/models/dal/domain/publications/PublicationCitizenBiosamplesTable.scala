package models.dal.domain.publications

import models.domain.publications.PublicationCitizenBiosample
import slick.jdbc.PostgresProfile.api.*

class PublicationCitizenBiosamplesTable(tag: Tag) extends Table[PublicationCitizenBiosample](tag, "publication_citizen_biosample") {
  def publicationId = column[Int]("publication_id")

  def citizenBiosampleId = column[Int]("citizen_biosample_id")

  def * = (publicationId, citizenBiosampleId).mapTo[PublicationCitizenBiosample]

  def pkey = primaryKey("publication_citizen_biosample_pkey", (publicationId, citizenBiosampleId))
}
