package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.dal.domain.haplogroups.HaplogroupsTable
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import play.api.libs.json.JsValue
import slick.ast.BaseTypedType
import slick.jdbc.JdbcType

import java.time.Instant

/**
 * Slick table definition for the `variant_v2` table.
 *
 * This table stores consolidated variants with JSONB columns for coordinates
 * (supporting multiple reference genomes) and aliases (supporting multiple
 * naming sources).
 *
 * Schema:
 * - One row per logical variant (not per reference genome)
 * - JSONB `coordinates` contains position + alleles per assembly
 * - JSONB `aliases` contains all known names grouped by source
 * - `defining_haplogroup_id` distinguishes parallel mutations
 */
class VariantV2Table(tag: Tag) extends Table[VariantV2](tag, Some("public"), "variant_v2") {

  // MappedColumnType for MutationType enum
  implicit val mutationTypeMapper: JdbcType[MutationType] with BaseTypedType[MutationType] =
    MappedColumnType.base[MutationType, String](
      _.dbValue,
      MutationType.fromStringOrDefault(_)
    )

  // MappedColumnType for NamingStatus enum
  implicit val namingStatusMapper: JdbcType[NamingStatus] with BaseTypedType[NamingStatus] =
    MappedColumnType.base[NamingStatus, String](
      _.dbValue,
      NamingStatus.fromStringOrDefault(_)
    )

  def variantId = column[Int]("variant_id", O.PrimaryKey, O.AutoInc)

  def canonicalName = column[Option[String]]("canonical_name")

  def mutationType = column[MutationType]("mutation_type")

  def namingStatus = column[NamingStatus]("naming_status")

  def aliases = column[JsValue]("aliases")

  def coordinates = column[JsValue]("coordinates")

  def definingHaplogroupId = column[Option[Int]]("defining_haplogroup_id")

  def evidence = column[JsValue]("evidence")

  def primers = column[JsValue]("primers")

  def notes = column[Option[String]]("notes")

  def annotations = column[JsValue]("annotations")

  def createdAt = column[Instant]("created_at")

  def updatedAt = column[Instant]("updated_at")

  def * = (
    variantId.?,
    canonicalName,
    mutationType,
    namingStatus,
    aliases,
    coordinates,
    definingHaplogroupId,
    evidence,
    primers,
    notes,
    annotations,
    createdAt,
    updatedAt
  ).mapTo[VariantV2]

  // Foreign key to haplogroup for parallel mutation disambiguation
  def definingHaplogroupFK = foreignKey(
    "variant_v2_defining_haplogroup_fk",
    definingHaplogroupId,
    TableQuery[HaplogroupsTable]
  )(_.haplogroupId.?, onDelete = ForeignKeyAction.SetNull)
}
