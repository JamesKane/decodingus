package models.dal.domain

import models.domain.Project
import slick.jdbc.PostgresProfile.api.*
import java.time.LocalDateTime
import java.util.UUID

class ProjectTable(tag: Tag) extends Table[Project](tag, "project") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def projectGuid = column[UUID]("project_guid", O.Unique)
  def name = column[String]("name")
  def description = column[Option[String]]("description")
  def ownerDid = column[String]("owner_did")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")
  def deleted = column[Boolean]("deleted", O.Default(false))
  def atCid = column[Option[String]]("at_cid")

  def * = (
    id.?,
    projectGuid,
    name,
    description,
    ownerDid,
    createdAt,
    updatedAt,
    deleted,
    atCid
  ).mapTo[Project]
}
