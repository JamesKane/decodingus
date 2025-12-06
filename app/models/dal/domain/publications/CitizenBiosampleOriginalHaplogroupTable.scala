package models.dal.domain.publications

import models.dal.MyPostgresProfile.api.*
import models.dal.domain.genomics.CitizenBiosamplesTable
import models.domain.genomics.HaplogroupResult
import models.domain.publications.CitizenBiosampleOriginalHaplogroup

class CitizenBiosampleOriginalHaplogroupTable(tag: Tag)
  extends Table[CitizenBiosampleOriginalHaplogroup](tag, "citizen_biosample_original_haplogroup") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def citizenBiosampleId = column[Int]("citizen_biosample_id")
  def publicationId = column[Int]("publication_id")
  def originalYHaplogroup = column[Option[HaplogroupResult]]("y_haplogroup_result")
  def originalMtHaplogroup = column[Option[HaplogroupResult]]("mt_haplogroup_result")
  def notes = column[Option[String]]("notes")

  // Foreign key relationships
  def citizenBiosample = foreignKey(
    "citizen_biosample_original_haplogroup_citizen_biosample_id_fkey",
    citizenBiosampleId,
    TableQuery[CitizenBiosamplesTable])(_.id, onDelete = ForeignKeyAction.Cascade)

  def publication = foreignKey(
    "citizen_biosample_original_haplogroup_publication_id_fkey",
    publicationId,
    TableQuery[PublicationsTable])(_.id, onDelete = ForeignKeyAction.Cascade)

  def uniqueCitizenBiosamplePublication = index(
    "citizen_biosample_original_haplogroup_cb_id_publication_id_key",
    (citizenBiosampleId, publicationId),
    unique = true
  )

  def * = (
    id.?,
    citizenBiosampleId,
    publicationId,
    originalYHaplogroup,
    originalMtHaplogroup,
    notes
  ).mapTo[CitizenBiosampleOriginalHaplogroup]
}
