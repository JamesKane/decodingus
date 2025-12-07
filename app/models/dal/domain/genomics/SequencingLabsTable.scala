package models.dal.domain.genomics

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.SequencingLab
import slick.lifted.{ProvenShape, Tag}

import java.time.LocalDateTime

class SequencingLabsTable(tag: Tag) extends MyPostgresProfile.api.Table[SequencingLab](tag, "sequencing_lab") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def name = column[String]("name")

  def isD2c = column[Boolean]("is_d2c")

  def websiteUrl = column[Option[String]]("website_url")

  def descriptionMarkdown = column[Option[String]]("description_markdown")

  def createdAt = column[LocalDateTime]("created_at")

  def updatedAt = column[Option[LocalDateTime]]("updated_at")

  override def * : ProvenShape[SequencingLab] = (
    id.?,
    name,
    isD2c,
    websiteUrl,
    descriptionMarkdown,
    createdAt,
    updatedAt
  ).mapTo[SequencingLab]

  // Unique constraint on name
  def nameIdx = index("sequencing_lab_name_key", name, unique = true)
}