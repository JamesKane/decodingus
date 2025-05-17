package models.dal

import models.HaplogroupVariantMetadata
import models.dal.MyPostgresProfile.api.*

import java.time.LocalDateTime

/**
 * Represents the table definition for the "haplogroup_variant_metadata" database table, which stores metadata
 * about changes or revisions associated with haplogroup variants.
 *
 * This class extends Slick's `Table` and provides column mappings, primary keys, and foreign key constraints
 * for the underlying database table.
 *
 * Table columns:
 * - `haplogroup_variant_id`: The unique identifier for the associated haplogroup variant.
 * - `revision_id`: An integer indicating the revision number for the haplogroup variant.
 * - `author`: The name or identifier of the person or entity responsible for the revision.
 * - `timestamp`: A `LocalDateTime` value indicating when the revision occurred.
 * - `comment`: A textual comment or description associated with the revision.
 * - `change_type`: A string describing the type of change, e.g., 'update', 'create', or 'delete'.
 * - `previous_revision_id`: An optional identifier indicating the previous revision in the sequence, if any.
 *
 * Primary key:
 * - A composite primary key based on `haplogroup_variant_id` and `revision_id`.
 *
 * Foreign key constraints:
 * - `haplogroup_variant_id` references the primary key of the `haplogroup_variant` table,
 * with update actions restricted and delete actions cascaded.
 *
 * Mapping:
 * - Maps the columns to the corresponding fields of the `HaplogroupVariantMetadata` case class.
 *
 * The table is used to track and manage revisions or updates made to haplogroup variants, enabling a history
 * of changes to be stored for audit and reference purposes.
 */
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