package models.dal.domain.pangenome

import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.PangenomeNode

class PangenomeNodesTable(tag: Tag) extends Table[PangenomeNode](tag, "pangenome_node") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def graphId = column[Int]("graph_id")

  def sequence = column[String]("sequence")

  def length = column[Int]("length")

  def isCore = column[Option[Boolean]]("is_core")

  def annotationId = column[Option[Int]]("annotation_id")

  def * = (
    id.?,
    graphId,
    sequence,
    length,
    isCore,
    annotationId
  ).mapTo[PangenomeNode]
}