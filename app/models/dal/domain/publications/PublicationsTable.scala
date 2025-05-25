package models.dal.domain.publications

import models.domain.Publication
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDate

/**
 * Represents the schema for the "publication" table, which stores metadata about scientific publications.
 *
 * This class maps the attributes of the `Publication` case class to columns in the database table,
 * enabling interaction with publication data such as identifiers, metadata, and publication details.
 *
 * Table columns:
 * - `id`: Auto-incrementing primary key, unique identifier for each publication.
 * - `pubmedId`: Optional unique identifier linking a publication to its PubMed record.
 * - `doi`: Optional unique Digital Object Identifier of the publication.
 * - `title`: Title of the publication. This is required.
 * - `authors`: Optional string representing the authors of the publication.
 * - `abstractSummary`: Optional summary or abstract describing the publication.
 * - `journal`: Optional name of the journal where the publication appeared.
 * - `publicationDate`: Optional date when the publication was officially released.
 * - `url`: Optional URL pointing to the online resource for the publication.
 *
 * Primary key:
 * - The `id` column serves as the primary key for the table.
 *
 * Constraints:
 * - `pubmedId` and `doi` columns are marked as unique.
 *
 * Table mapping:
 * - Maps all columns to a `Publication` case class using the Slick `mapTo` method, ensuring seamless
 * conversion between database rows and application-level objects.
 */
class PublicationsTable(tag: Tag) extends Table[Publication](tag, "publication") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def pubmedId = column[Option[String]]("pubmed_id", O.Unique)

  def doi = column[Option[String]]("doi", O.Unique)

  def title = column[String]("title")

  def authors = column[Option[String]]("authors")

  def abstractSummary = column[Option[String]]("abstract_summary")

  def journal = column[Option[String]]("journal")

  def publicationDate = column[Option[LocalDate]]("publication_date")

  def url = column[Option[String]]("url")

  def * = (id.?, pubmedId, doi, title, authors, abstractSummary, journal, publicationDate, url).mapTo[Publication]
}
