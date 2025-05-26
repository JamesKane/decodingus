package models.dal.domain.publications

import models.domain.publications.Publication
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDate

class PublicationsTable(tag: Tag) extends Table[Publication](tag, "publication") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def openAlexId = column[Option[String]]("open_alex_id", O.Unique)

  def pubmedId = column[Option[String]]("pubmed_id", O.Unique)

  def doi = column[Option[String]]("doi", O.Unique)

  def title = column[String]("title")

  def authors = column[Option[String]]("authors")

  def abstractSummary = column[Option[String]]("abstract_summary")

  def journal = column[Option[String]]("journal")

  def publicationDate = column[Option[LocalDate]]("publication_date")

  def url = column[Option[String]]("url")

  def citationNormalizedPercentile = column[Option[Float]]("citation_normalized_percentile")

  def citedByCount = column[Option[Int]]("cited_by_count")

  def openAccessStatus = column[Option[String]]("open_access_status")

  def openAccessUrl = column[Option[String]]("open_access_url")

  def primaryTopic = column[Option[String]]("primary_topic") // NEW column

  def publicationType = column[Option[String]]("publication_type")

  def publisher = column[Option[String]]("publisher")


  // Update the * projection to include all new columns and remove old ones
  def * = (
    id.?,
    openAlexId,
    pubmedId,
    doi,
    title,
    authors,
    abstractSummary,
    journal,
    publicationDate,
    url,
    citationNormalizedPercentile,
    citedByCount,
    openAccessStatus,
    openAccessUrl,
    primaryTopic, // Updated here
    publicationType,
    publisher
  ).mapTo[Publication]
}
