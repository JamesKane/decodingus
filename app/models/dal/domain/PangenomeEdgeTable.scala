package models.dal.domain

import models.dal.MyPostgresProfile.api.*
import models.domain.PangenomeEdge

class PangenomeEdgesTable(tag: Tag) extends Table[PangenomeEdge](tag, "pangenome_edge") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def graphId = column[Int]("graph_id")

  def sourceNodeId = column[Int]("source_node_id")

  def targetNodeId = column[Int]("target_node_id")

  def sourceOrientation = column[String]("source_orientation")

  def targetOrientation = column[String]("target_orientation")

  def edgeType = column[Option[String]]("type")

  // Define the composite unique constraint as an index
  def uniqueEdge = index("idx_unique_pangenome_edge", (graphId, sourceNodeId, targetNodeId, sourceOrientation, targetOrientation), unique = true)

  def * = (
    id.?,
    graphId,
    sourceNodeId,
    targetNodeId,
    sourceOrientation,
    targetOrientation,
    edgeType // Use the new field name
  ).mapTo[PangenomeEdge]
}