package models.dal

import models.PangenomeGraph
import models.dal.MyPostgresProfile.api.*

import java.time.ZonedDateTime

class PangenomeGraphsTable(tag: Tag) extends Table[PangenomeGraph](tag, "pangenome_graph") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def name = column[String]("name", O.Unique)

  def description = column[Option[String]]("description")

  def creationDate = column[ZonedDateTime]("creation_date")

  def checksum = column[Option[String]]("checksum")

  def * = (
    id.?, // Primary key as Option[Long]
    name,
    description,
    creationDate,
    checksum
  ).mapTo[PangenomeGraph] // Using .mapTo
}