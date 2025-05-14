package models.dal

import models.PublicationEnaStudy
import slick.jdbc.PostgresProfile.api.*

class PublicationEnaStudiesTable(tag: Tag) extends Table[PublicationEnaStudy] (tag, "publication_ena_study") {
  def publicationId = column[Int]("publication_id")
  def enaStudyId = column[Int]("ena_study_id")
  
  def * = (publicationId, enaStudyId).mapTo[PublicationEnaStudy]
  
  def pkey = primaryKey("publication_ena_study_pkey", (publicationId, enaStudyId))
}
