package models.dal

import models.PublicationBiosample
import slick.jdbc.PostgresProfile.api.*

class PublicationBiosamplesTable(tag: Tag) extends Table[PublicationBiosample](tag, "publication_biosample") {
  def publicationId = column[Int]("publication_id")
  def biosampleId = column[Int]("biosample_id")
  
  def * = (publicationId, biosampleId).mapTo[PublicationBiosample]
  
  def pkey = primaryKey("publication_biosample_pkey", (publicationId, biosampleId))
}
