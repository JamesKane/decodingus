package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Public API response for a variant coordinate in a specific reference assembly.
 * Includes ref/alt alleles per assembly to handle strand differences (e.g., CHM13 reverse complements).
 *
 * @param contig   The contig/chromosome name (e.g., "chrY")
 * @param position The 1-based position on the contig
 * @param ref      The reference allele in THIS assembly (may differ from other assemblies)
 * @param alt      The alternate/derived allele in THIS assembly
 */
case class VariantCoordinateDTO(
  contig: String,
  position: Int,
  ref: String,
  alt: String
)

object VariantCoordinateDTO {
  implicit val format: OFormat[VariantCoordinateDTO] = Json.format[VariantCoordinateDTO]
}

/**
 * Public API response for variant aliases grouped by source.
 *
 * @param commonNames  SNP names from various sources (e.g., ["M269", "S21"])
 * @param rsIds        dbSNP identifiers (e.g., ["rs9786076"])
 * @param sources      Names grouped by source (e.g., {"ybrowse": ["M269"], "ftdna": ["S312"]})
 */
case class VariantAliasesDTO(
  commonNames: Seq[String] = Seq.empty,
  rsIds: Seq[String] = Seq.empty,
  sources: Map[String, Seq[String]] = Map.empty
)

object VariantAliasesDTO {
  implicit val format: OFormat[VariantAliasesDTO] = Json.format[VariantAliasesDTO]
}

/**
 * Public API response for a variant's haplogroup association.
 * Included when the variant defines a haplogroup.
 *
 * @param haplogroupId   The haplogroup database ID
 * @param haplogroupName The haplogroup name (e.g., "R-M269", "I-L21")
 */
case class DefiningHaplogroupDTO(
  haplogroupId: Int,
  haplogroupName: String
)

object DefiningHaplogroupDTO {
  implicit val format: OFormat[DefiningHaplogroupDTO] = Json.format[DefiningHaplogroupDTO]
}

/**
 * Public API response for a single variant.
 *
 * Designed to be forward-compatible with the proposed variant_v2 schema:
 * - canonicalName as primary identifier (nullable for unnamed variants)
 * - coordinates as map with per-assembly alleles (handles strand differences)
 * - aliases as structured object (not separate table)
 * - definingHaplogroup for parallel mutation support
 *
 * @param variantId          Internal variant ID (for reference/linking)
 * @param canonicalName      Primary name (e.g., "M269"); null for unnamed/novel variants
 * @param variantType        Mutation type: "SNP", "INDEL", "MNP"
 * @param namingStatus       Status: "NAMED", "UNNAMED", or "PENDING_REVIEW"
 * @param coordinates        Map of reference genome -> coordinates with alleles
 * @param aliases            Alternative names grouped by type/source
 * @param definingHaplogroup The haplogroup this variant defines (if any)
 */
case class PublicVariantDTO(
  variantId: Int,
  canonicalName: Option[String],
  variantType: String,
  namingStatus: String,
  coordinates: Map[String, VariantCoordinateDTO],
  aliases: VariantAliasesDTO,
  definingHaplogroup: Option[DefiningHaplogroupDTO] = None
)

object PublicVariantDTO {
  implicit val format: OFormat[PublicVariantDTO] = Json.format[PublicVariantDTO]
}

/**
 * Paginated response for variant search/list.
 *
 * @param items       The variants for the current page
 * @param currentPage Current page number (1-based)
 * @param pageSize    Items per page
 * @param totalItems  Total matching variants
 * @param totalPages  Total pages available
 */
case class VariantSearchResponse(
  items: Seq[PublicVariantDTO],
  currentPage: Int,
  pageSize: Int,
  totalItems: Long,
  totalPages: Int
)

object VariantSearchResponse {
  implicit val format: OFormat[VariantSearchResponse] = Json.format[VariantSearchResponse]
}
