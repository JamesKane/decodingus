package models.dal

import models.GeneAnnotation
import models.dal.MyPostgresProfile.api.*

class GeneAnnotationsTable(tag: Tag) extends Table[GeneAnnotation](tag, "gene_annotation") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def geneSymbol = column[Option[String]]("gene_symbol")

  def geneId = column[Option[String]]("gene_id")

  def description = column[Option[String]]("description")

  def representativeSequenceNodeId = column[Option[Int]]("representative_sequence_node_id")

  def * = (
    id.?,
    geneSymbol,
    geneId,
    description,
    representativeSequenceNodeId
  ).mapTo[GeneAnnotation]
}