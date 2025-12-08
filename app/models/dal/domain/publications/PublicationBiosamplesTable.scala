package models.dal.domain.publications

import models.domain.publications.PublicationBiosample
import models.dal.MyPostgresProfile.api.*

/**
 * Represents the database table definition for associating publications with biosamples.
 *
 * This table facilitates a many-to-many relationship between publications and biosamples, where each
 * association is identified by a composite primary key consisting of a `publicationId` and a `biosampleId`.
 *
 * @constructor Initializes a new instance of the `PublicationBiosamplesTable` class, mapping columns
 *              to the attributes of the `PublicationBiosample` case class.
 * @param tag A Slick `Tag` object used to scope and reference the table within a database schema.
 *
 *            Columns:
 *  - `publicationId`: Integer column representing the unique identifier of the associated publication.
 *  - `biosampleId`: Integer column representing the unique identifier of the associated biosample.
 *
 * Primary key:
 *  - Composite primary key composed of the `publicationId` and `biosampleId` columns.
 *
 * Mapping:
 *  - Defines a mapping to the `PublicationBiosample` case class.
 */
class PublicationBiosamplesTable(tag: Tag) extends Table[PublicationBiosample](tag, "publication_biosample") {
  def publicationId = column[Int]("publication_id")

  def biosampleId = column[Int]("biosample_id")

  def * = (publicationId, biosampleId).mapTo[PublicationBiosample]

  def pkey = primaryKey("publication_biosample_pkey", (publicationId, biosampleId))
}
