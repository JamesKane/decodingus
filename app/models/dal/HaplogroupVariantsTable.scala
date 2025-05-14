package models.dal

import models.{Haplogroup, Variant, HaplogroupVariant}
import slick.jdbc.PostgresProfile.api._

class HaplogroupVariantsTable(tag: Tag) extends Table[HaplogroupVariant](tag, "haplogroup_variant") {
  def haplogroupVariantId = column[Int]("haplogroup_variant_id", O.PrimaryKey, O.AutoInc)
  def haplogroupId = column[Int]("haplogroup_id")
  def variantId = column[Int]("variant_id")

  def * = (haplogroupVariantId.?, haplogroupId, variantId).mapTo[HaplogroupVariant]

  def haplogroupFK = foreignKey("haplogroup_fk", haplogroupId, TableQuery[HaplogroupsTable])(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)
  def variantFK = foreignKey("variant_fk", variantId, TableQuery[VariantsTable])(_.variantId, onDelete = ForeignKeyAction.Cascade)

  def uniqueHaplogroupVariant = index("unique_haplogroup_variant", (haplogroupId, variantId), unique = true)
}
