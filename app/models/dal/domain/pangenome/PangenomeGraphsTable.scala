package models.dal.domain.pangenome

import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.PangenomeGraph

import java.time.ZonedDateTime

class PangenomeGraphsTable(tag: Tag) extends Table[PangenomeGraph](tag, "pangenome_graph") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def graphName = column[String]("graph_name")

  def sourceGfaFile = column[Option[String]]("source_gfa_file")

  def description = column[Option[String]]("description")

  def creationDate = column[ZonedDateTime]("creation_date")

  def * = (
    id.?,
    graphName,
    sourceGfaFile,
    description,
    creationDate
  ).mapTo[PangenomeGraph]
}