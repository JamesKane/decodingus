package models.dal.domain.publications

import models.dal.MyPostgresProfile.api.*
import models.domain.publications.{GenomicStudy, StudySource}

/**
 * Represents the database table definition for genomic studies.
 *
 * This table stores metadata related to genomic studies, including accession numbers, titles,
 * study details, submission and update dates, as well as source-specific attributes such as
 * project IDs, molecular information, and taxonomy identifiers.
 *
 * Columns:
 * - `id`: An auto-incrementing primary key that uniquely identifies each genomic study.
 * - `accession`: A unique accession number for the genomic study, providing a reference for databases.
 * - `title`: The title of the genomic study, offering a summary or description.
 * - `centerName`: The name of the research center or institution responsible for the study.
 * - `studyName`: A specific or additional name assigned to the genomic study.
 * - `details`: A textual description containing detailed information about the study.
 * - `source`: The source of the genomic study data, represented using the `StudySource` enumeration.
 * - `submissionDate`: An optional field capturing the date when the study was submitted.
 * - `lastUpdate`: An optional field storing the date of the most recent update for the study information.
 * - `bioProjectId`: An optional field containing the BioProject ID associated with the study.
 * - `molecule`: An optional field describing the type of molecule studied in the project.
 * - `topology`: An optional field containing information about the molecular topology.
 * - `taxonomyId`: An optional field referencing the taxonomy ID associated with the study.
 * - `version`: An optional field containing the version or release information for the study.
 *
 * Primary key:
 * - The `id` column serves as the primary key, which is auto-incremented.
 *
 * Mapping:
 * - Defines a mapping to the `GenomicStudy` case class, which models the domain representation
 * of the genomic study entity.
 *
 * Table Name:
 * - The physical table name in the database is `genomic_studies`.
 *
 * This table can be used in conjunction with other table definitions such as `PublicationEnaStudiesTable`
 * to establish relationships between genomic studies and publications.
 */
class GenomicStudiesTable(tag: Tag) extends Table[GenomicStudy](tag, "genomic_studies") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def accession = column[String]("accession", O.Unique)

  def title = column[String]("title")

  def centerName = column[String]("center_name")

  def studyName = column[String]("study_name")

  def details = column[String]("details")

  def source = column[StudySource]("source")

  def submissionDate = column[Option[java.time.LocalDate]]("submission_date")

  def lastUpdate = column[Option[java.time.LocalDate]]("last_update")

  def bioProjectId = column[Option[String]]("bio_project_id")

  def molecule = column[Option[String]]("molecule")

  def topology = column[Option[String]]("topology")

  def taxonomyId = column[Option[Int]]("taxonomy_id")

  def version = column[Option[String]]("version")


  def * = (
    id.?,
    accession,
    title,
    centerName,
    studyName,
    details,
    source,
    submissionDate,
    lastUpdate,
    bioProjectId,
    molecule,
    topology,
    taxonomyId,
    version
  ).mapTo[GenomicStudy]
}
