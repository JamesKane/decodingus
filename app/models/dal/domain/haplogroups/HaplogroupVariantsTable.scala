package models.dal.domain.haplogroups

import models.dal.MyPostgresProfile.api.*
import models.dal.domain.genomics.{Variant, VariantsTable}
import models.domain.haplogroups.{Haplogroup, HaplogroupVariant}

/**
 * Represents the mapping for the `haplogroup_variant` table in the database. This table defines
 * an associative relationship between haplogroups and genetic variants, allowing each haplogroup
 * to be linked to specific variants.
 *
 * @constructor Initializes the Slick table mapping for `haplogroup_variant`.
 * @param tag A Slick `Tag` object containing meta-information used internally for query construction.
 *
 *            Table columns:
 *            - `haplogroupVariantId`: Auto-incrementing primary key for the association (integer).
 *            - `haplogroupId`: Foreign key referencing the `haplogroup_id` in the `haplogroups` table.
 *            - `variantId`: Foreign key referencing the `variant_id` in the `variant` table.
 *
 *            Relationships:
 *            - `haplogroupFK`: Defines a foreign key constraint on the `haplogroupId` column, referencing
 *              the `haplogroup_id` column in the `HaplogroupsTable`. Automatically cascades deletes.
 *            - `variantFK`: Defines a foreign key constraint on the `variantId` column, referencing the
 *              `variant_id` column in the `VariantsTable`. Automatically cascades deletes.
 *
 *            Indexes:
 *            - `uniqueHaplogroupVariant`: Ensures unique associations between haplogroups and variants, 
 *              preventing duplicate entries for the same pair of `haplogroupId` and `variantId`.
 *
 *            Slick mapping:
 *            - The `*` projection maps table rows to the `HaplogroupVariant` case class.
 */
class HaplogroupVariantsTable(tag: Tag) extends Table[HaplogroupVariant](tag, Some("tree"), "haplogroup_variant") {
  def haplogroupVariantId = column[Int]("haplogroup_variant_id", O.PrimaryKey, O.AutoInc)

  def haplogroupId = column[Int]("haplogroup_id")

  def variantId = column[Int]("variant_id")

  def * = (haplogroupVariantId.?, haplogroupId, variantId).mapTo[HaplogroupVariant]

  def haplogroupFK = foreignKey("haplogroup_fk", haplogroupId, TableQuery[HaplogroupsTable])(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)

  // Explicitly specify the schema for VariantsTable which is in the public schema
  def variantFK = foreignKey("variant_fk", variantId, TableQuery[VariantsTable])(_.variantId, onDelete = ForeignKeyAction.Cascade)

  def uniqueHaplogroupVariant = index("unique_haplogroup_variant", (haplogroupId, variantId), unique = true)
}
