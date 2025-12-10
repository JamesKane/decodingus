package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*

import java.time.LocalDateTime

/**
 * Represents the `variant_alias` table in the database, which stores alternative names
 * for genetic variants from different sources (YBrowse, ISOGG, YFull, publications, etc.).
 *
 * @param tag A Slick `Tag` object used to scope and reference the table within a database schema.
 */
class VariantAliasTable(tag: Tag) extends Table[VariantAlias](tag, Some("public"), "variant_alias") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def variantId = column[Int]("variant_id")

  def aliasType = column[String]("alias_type")

  def aliasValue = column[String]("alias_value")

  def source = column[Option[String]]("source")

  def isPrimary = column[Boolean]("is_primary")

  def createdAt = column[LocalDateTime]("created_at")

  def * = (id.?, variantId, aliasType, aliasValue, source, isPrimary, createdAt).mapTo[VariantAlias]

  def variantFK = foreignKey("variant_alias_variant_fk", variantId, TableQuery[VariantsTable])(_.variantId, onDelete = ForeignKeyAction.Cascade)

  def uniqueAlias = index("variant_alias_unique", (variantId, aliasType, aliasValue), unique = true)
}
