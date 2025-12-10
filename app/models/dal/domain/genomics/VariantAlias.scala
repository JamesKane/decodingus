package models.dal.domain.genomics

import java.time.LocalDateTime

/**
 * Represents an alternative name (alias) for a variant.
 *
 * Variants are often known by multiple names across different research groups and databases:
 * - ISOGG names (e.g., M269, P312)
 * - YFull names (e.g., BY12345)
 * - FTDNA names
 * - dbSNP rsIDs (e.g., rs9786076)
 * - Publication-specific identifiers
 *
 * This model allows tracking all known names for a variant while maintaining
 * a primary display name.
 *
 * @param id          Unique identifier for this alias record
 * @param variantId   The variant this alias belongs to
 * @param aliasType   Type of alias: 'common_name', 'rs_id', 'isogg', 'yfull', 'ftdna', etc.
 * @param aliasValue  The actual alias value (e.g., "M269", "rs9786076")
 * @param source      Where this alias came from: 'ybrowse', 'isogg', 'curator', 'migration', etc.
 * @param isPrimary   Whether this is the primary alias for its type (used for display)
 * @param createdAt   When this alias was recorded
 */
case class VariantAlias(
    id: Option[Int] = None,
    variantId: Int,
    aliasType: String,
    aliasValue: String,
    source: Option[String] = None,
    isPrimary: Boolean = false,
    createdAt: LocalDateTime = LocalDateTime.now()
)

object VariantAliasType {
  val CommonName = "common_name"
  val RsId = "rs_id"
  val Isogg = "isogg"
  val YFull = "yfull"
  val Ftdna = "ftdna"
  val Publication = "publication"
}
