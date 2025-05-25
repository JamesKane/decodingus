package models.dal.domain.pangenome

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.CanonicalPangenomeVariant

import java.time.ZonedDateTime

class CanonicalPangenomeVariantsTable(tag: Tag) extends Table[CanonicalPangenomeVariant](tag, "canonical_pangenome_variant") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def panGenomeGraphId = column[Int]("pangenome_graph_id")

  def variantType = column[String]("variant_type")

  def variantNodes = column[List[Int]]("variant_nodes")(MyPostgresProfile.api.intListTypeMapper)

  def variantEdges = column[List[Int]]("variant_edges")(MyPostgresProfile.api.intListTypeMapper)

  def referencePathId = column[Option[Int]]("reference_path_id")

  def referenceStartPosition = column[Option[Int]]("reference_start_position")

  def referenceEndPosition = column[Option[Int]]("reference_end_position")

  def referenceAlleleSequence = column[Option[String]]("reference_allele_sequence")

  def alternateAlleleSequence = column[Option[String]]("alternate_allele_sequence")

  def canonicalHash = column[String]("canonical_hash")

  def description = column[Option[String]]("description")

  def creationDate = column[ZonedDateTime]("creation_date")

  def * = (
    id.?,
    panGenomeGraphId,
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

}
