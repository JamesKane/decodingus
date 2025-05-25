package models.dal

import models.PangenomeVariantLink
import models.dal.MyPostgresProfile.api.*

import java.time.ZonedDateTime

class PangenomeVariantLinksTable(tag: Tag) extends Table[PangenomeVariantLink](tag, "pangenome_variant_link") {
  def id = column[Long]("pangenome_variant_link_id", O.PrimaryKey, O.AutoInc)

  def variantId = column[Int]("variant_id")

  def canonicalPangenomeVariantId = column[Int]("canonical_pangenome_variant_id")

  def pangenomeGraphId = column[Int]("pangenome_graph_id")

  def description = column[Option[String]]("description")

  def mappingSource = column[String]("mapping_source")

  def mappingDate = column[ZonedDateTime]("mapping_date")

  // Define the composite unique constraint
  def uniqueLink = index("idx_unique_pangenome_variant_link", (variantId, canonicalPangenomeVariantId), unique = true)

  def * = (
    id.?,
    variantId,
    canonicalPangenomeVariantId,
    pangenomeGraphId,
    description,
    mappingSource,
    mappingDate
  ).mapTo[PangenomeVariantLink]
}