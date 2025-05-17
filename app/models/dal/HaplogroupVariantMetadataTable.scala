package models.dal

import models.HaplogroupVariantMetadata
import models.dal.MyPostgresProfile.api.*

import java.time.LocalDateTime

class HaplogroupVariantMetadataTable(tag: Tag) extends Table[HaplogroupVariantMetadata](tag, "haplogroup_variant_metadata") {
  def haplogroup_variant_id = column[Int]("haplogroup_variant_id")
  def revision_id = column[Int]("revision_id")
  def author = column[String]("author")
  def timestamp = column[LocalDateTime]("timestamp")
  def comment = column[String]("comment")
  def change_type = column[String]("change_type")
  def previous_revision_id = column[Option[Int]]("previous_revision_id")

  def pk = primaryKey("pk_haplogroup_variant_metadata", (haplogroup_variant_id, revision_id))
  def fk = foreignKey(
    "fk_haplogroup_variant_metadata_variant",
    haplogroup_variant_id,
    TableQuery[HaplogroupVariantsTable])(_.haplogroupVariantId,
    onUpdate = ForeignKeyAction.Restrict,
    onDelete = ForeignKeyAction.Cascade
  )

  def * = (
    haplogroup_variant_id,
    revision_id,
    author,
    timestamp,
    comment,
    change_type,
    previous_revision_id
  ).mapTo[HaplogroupVariantMetadata]
}