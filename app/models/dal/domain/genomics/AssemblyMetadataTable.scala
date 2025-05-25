package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.AssemblyMetadata
import play.api.libs.json.JsValue

import java.time.LocalDate

class AssemblyMetadataTable(tag: Tag) extends Table[AssemblyMetadata](tag, "assembly_metadata") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def assemblyName = column[String]("assembly_name", O.Unique)

  def accession = column[Option[String]]("accession")

  def releaseDate = column[Option[LocalDate]]("release_date")

  def sourceOrganism = column[Option[String]]("source_organism")

  def assemblyLevel = column[Option[String]]("assembly_level")

  def metadata = column[Option[JsValue]]("metadata")

  def * = (
    id.?,
    assemblyName,
    accession,
    releaseDate,
    sourceOrganism,
    assemblyLevel,
    metadata
  ).mapTo[AssemblyMetadata]
}