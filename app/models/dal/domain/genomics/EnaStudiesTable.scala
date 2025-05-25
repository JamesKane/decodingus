package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.EnaStudy

/**
 * Represents the database table definition for storing ENA (European Nucleotide Archive) studies.
 *
 * @constructor Initializes a new instance of the `EnaStudiesTable` class, mapping columns
 *              to the attributes of an `EnaStudy` entity.
 * @param tag A Slick `Tag` object used to scope and reference the table within the database schema.
 *
 *            Columns:
 *  - `id`: Unique identifier for the study (primary key, auto-increment).
 *  - `accession`: Unique accession string for the study, used as a reference in databases.
 *  - `title`: The title of the study, summarizing its content or purpose.
 *  - `centerName`: The name of the center or institution responsible for the study.
 *  - `studyName`: Descriptive name of the study, providing additional context or detail.
 *  - `details`: A field for textual description or additional metadata regarding the study.
 *
 * Primary key:
 *  - `id`: The primary key for the table, auto-generated.
 *
 * Mapping:
 *  - Defines a mapping to the `EnaStudy` case class.
 */
class EnaStudiesTable(tag: Tag) extends Table[EnaStudy](tag, "ena_study") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def accession = column[String]("accession", O.Unique)

  def title = column[String]("title")

  def centerName = column[String]("center_name")

  def studyName = column[String]("study_name")

  def details = column[String]("details")

  def * = (id.?, accession, title, centerName, studyName, details).mapTo[EnaStudy]
}
