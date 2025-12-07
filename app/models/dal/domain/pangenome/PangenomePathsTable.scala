package models.dal.domain.pangenome

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.PangenomePath

class PangenomePathsTable(tag: Tag) extends Table[PangenomePath](tag, "pangenome_path") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def graphId = column[Long]("graph_id")

  def pathName = column[String]("path_name")

  def isReference = column[Boolean]("is_reference")

  def lengthBp = column[Option[Long]]("length_bp")

  def description = column[Option[String]]("description")

  def * = (
    id.?,
    graphId,
    pathName,
    isReference,
    lengthBp,
    description
  ).mapTo[PangenomePath]

  def graphFk = foreignKey("fk_path_graph", graphId, TableQuery[PangenomeGraphsTable])(_.id)
}
