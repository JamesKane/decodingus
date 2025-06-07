package models.dal.domain.pangenome

import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.CanonicalPangenomeVariant

import java.time.ZonedDateTime

class CanonicalPangenomeVariantsTable(tag: Tag) extends Table[CanonicalPangenomeVariant](tag, "canonical_pangenome_variant") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def pangenomeGraphId = column[Long]("pangenome_graph_id")
  def variantType = column[String]("variant_type")
  def variantNodes = column[List[Int]]("variant_nodes")
  def variantEdges = column[List[Int]]("variant_edges")
  def referencePathId = column[Option[Long]]("reference_path_id")
  def referenceStartPosition = column[Option[Int]]("reference_start_position")
  def referenceEndPosition = column[Option[Int]]("reference_end_position")
  def referenceAlleleSequence = column[Option[String]]("reference_allele_sequence")
  def alternateAlleleSequence = column[Option[String]]("alternate_allele_sequence")
  def canonicalHash = column[String]("canonical_hash")
  def description = column[Option[String]]("description")
  def creationDate = column[ZonedDateTime]("creation_date")

  def * = (
    id.?,
    pangenomeGraphId,
    variantType,
    variantNodes,
    variantEdges,
    referencePathId,
    referenceStartPosition,
    referenceEndPosition,
    referenceAlleleSequence,
    alternateAlleleSequence,
    canonicalHash,
    description,
    creationDate
  ).mapTo[CanonicalPangenomeVariant]

  def graphFk = foreignKey("fk_variant_graph", pangenomeGraphId, TableQuery[PangenomeGraphsTable])(_.id)
  def pathFk = foreignKey("fk_variant_path", referencePathId, TableQuery[PangenomePathsTable])(_.id.?)
}

