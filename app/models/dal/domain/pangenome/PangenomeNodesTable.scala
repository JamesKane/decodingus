package models.dal.domain.pangenome

import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.PangenomeNode

class PangenomeNodesTable(tag: Tag) extends Table[PangenomeNode](tag, "pangenome_node") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def graphId = column[Long]("graph_id")

  def nodeName = column[String]("node_name")

  def sequenceLength = column[Option[Long]]("sequence_length")

  def * = (
    id.?,
    graphId,
    nodeName,
    sequenceLength
  ).mapTo[PangenomeNode]

  def graphFk = foreignKey("fk_node_graph", graphId, TableQuery[PangenomeGraphsTable])(_.id)
}
