package models.dal.domain.publications

import models.domain.publications.PublicationEnaStudy
import slick.jdbc.PostgresProfile.api.*

/**
 * Represents the database table definition for storing the relationship between publications
 * and ENA (European Nucleotide Archive) studies.
 *
 * This table establishes a many-to-many relationship between publications and ENA studies,
 * enabling the association of publications with the corresponding study metadata in ENA.
 *
 * @constructor Creates an instance of `PublicationEnaStudiesTable`.
 * @param tag A Slick `Tag` object used to scope and reference the table within a database schema.
 *
 *            Table name:
 *            - The physical name of the table in the database is `publication_ena_study`.
 *
 *            Columns:
 *            - `publicationId`: Integer column representing the unique identifier of a publication.
 *            - `enaStudyId`: Integer column representing the unique identifier of an ENA study.
 *
 *            Primary key:
 *            - A composite primary key is defined using both `publicationId` and `enaStudyId`.
 *
 *            Mapping:
 *            - Defines a mapping to the `PublicationEnaStudy` case class, which represents the relationship
 *              between a publication and an ENA study in the application domain.
 */
class PublicationEnaStudiesTable(tag: Tag) extends Table[PublicationEnaStudy](tag, "publication_ena_study") {
  def publicationId = column[Int]("publication_id")

  def enaStudyId = column[Int]("ena_study_id")

  def * = (publicationId, enaStudyId).mapTo[PublicationEnaStudy]

  def pkey = primaryKey("publication_ena_study_pkey", (publicationId, enaStudyId))
}
