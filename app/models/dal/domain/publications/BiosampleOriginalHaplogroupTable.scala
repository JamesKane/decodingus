package models.dal.domain.publications

import models.dal.MyPostgresProfile.api.*
import models.dal.domain.genomics.BiosamplesTable
import models.domain.publications.BiosampleOriginalHaplogroup

/**
 * Represents the database table definition for original haplogroup assignments.
 *
 * This table stores the original haplogroup assignments for biosamples as reported in publications,
 * allowing tracking of how haplogroup assignments may have changed over time or differ between
 * publications.
 *
 * Columns:
 * - `id`: An auto-incrementing primary key
 * - `biosampleId`: Foreign key reference to the biosample table
 * - `publicationId`: Foreign key reference to the publication table
 * - `originalYHaplogroup`: The Y chromosome haplogroup as originally reported
 * - `originalMtHaplogroup`: The mitochondrial DNA haplogroup as originally reported
 * - `notes`: Optional text field for additional information
 *
 * Constraints:
 * - Primary key on `id`
 * - Foreign key constraints on `biosampleId` and `publicationId`
 * - Unique constraint on the combination of `biosampleId` and `publicationId`
 */
class BiosampleOriginalHaplogroupTable(tag: Tag)
  extends Table[BiosampleOriginalHaplogroup](tag, "biosample_original_haplogroup") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def biosampleId = column[Int]("biosample_id")

  def publicationId = column[Int]("publication_id")

  def originalYHaplogroup = column[Option[String]]("original_y_haplogroup")

  def originalMtHaplogroup = column[Option[String]]("original_mt_haplogroup")

  def notes = column[Option[String]]("notes")

  // Foreign key relationships
  def biosample = foreignKey(
    "biosample_original_haplogroup_biosample_id_fkey",
    biosampleId,
    TableQuery[BiosamplesTable])(_.id, onDelete = ForeignKeyAction.Cascade)

  def publication = foreignKey(
    "biosample_original_haplogroup_publication_id_fkey",
    publicationId,
    TableQuery[PublicationsTable])(_.id, onDelete = ForeignKeyAction.Cascade)

  // Unique constraint on biosample_id and publication_id combination
  def uniqueBiosamplePublication = index(
    "biosample_original_haplogroup_biosample_id_publication_id_key",
    (biosampleId, publicationId),
    unique = true
  )

  def * = (
    id.?,
    biosampleId,
    publicationId,
    originalYHaplogroup,
    originalMtHaplogroup,
    notes
  ).mapTo[BiosampleOriginalHaplogroup]
}
