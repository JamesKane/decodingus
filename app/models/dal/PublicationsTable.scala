package models.dal

import models.Publication
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDate

class PublicationsTable(tag: Tag) extends Table[Publication](tag, "publication") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def pubmedId = column[Option[String]]("pubmed_id", O.Unique)
  def doi = column[Option[String]]("doi", O.Unique)
  def title = column[String]("title")
  def journal = column[Option[String]]("journal")
  def publicationDate = column[Option[LocalDate]]("publication_date")
  def url = column[Option[String]]("url")
  
  def * = (id.?, pubmedId, doi, title, journal, publicationDate, url).mapTo[Publication]
}
