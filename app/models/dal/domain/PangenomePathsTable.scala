package models.dal.domain

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.PangenomePath

class PangenomePathsTable(tag: Tag) extends Table[PangenomePath](tag, "pangenome_path") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def graphId = column[Int]("graph_id")

  def name = column[String]("name")

  def nodeSequence = column[List[Int]]("node_sequence")(MyPostgresProfile.api.intListTypeMapper)

  def length = column[Long]("length")

  def sourceAssemblyId = column[Option[Int]]("source_assembly_id")

  def uniqueGraphName = index("idx_unique_pangenome_path_graph_name", (graphId, name), unique = true)

  def * = (
    id.?,
    graphId,
    name,
    nodeSequence,
    length,
    sourceAssemblyId
  ).mapTo[PangenomePath]
}