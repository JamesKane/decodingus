package models.domain.genomics

/**
 * Groups variants that represent the same logical SNP across different reference builds.
 * Variants are grouped by commonName (primary) or rsId (fallback).
 *
 * For example, M269 might have positions in GRCh37, GRCh38, and hs1,
 * each stored as a separate Variant row but logically the same marker.
 *
 * @param groupKey    The key used to group these variants (commonName or rsId)
 * @param variants    All variants (with their contig info) that share this group key
 * @param rsId        The rsId if present on any variant in the group
 * @param commonName  The common name if present on any variant in the group
 */
case class VariantGroup(
    groupKey: String,
    variants: Seq[VariantWithContig],
    rsId: Option[String],
    commonName: Option[String]
) {
  /**
   * Get all variant IDs in this group
   */
  def variantIds: Seq[Int] = variants.flatMap(_.variant.variantId)

  /**
   * Display name for the variant group (commonName preferred, rsId fallback)
   */
  def displayName: String = commonName.orElse(rsId).getOrElse(s"ID: ${variantIds.headOption.getOrElse("?")}")

  /**
   * Summary of all builds available (e.g., "GRCh37, GRCh38, hs1")
   */
  def buildSummary: String = variants
    .map(_.shortReferenceGenome)
    .distinct
    .sorted
    .mkString(", ")

  /**
   * Number of reference builds available for this variant
   */
  def buildCount: Int = variants.map(_.shortReferenceGenome).distinct.size

  /**
   * Variants sorted by reference genome for consistent display
   */
  def variantsSorted: Seq[VariantWithContig] = variants.sortBy { v =>
    v.shortReferenceGenome match {
      case "GRCh37" => 1
      case "GRCh38" => 2
      case "hs1"    => 3
      case other    => 4
    }
  }
}

object VariantGroup {
  /**
   * Creates variant groups from a sequence of variants with contig info.
   * Groups by commonName (primary), falling back to rsId.
   * Variants without either become single-variant groups keyed by variant ID.
   */
  def fromVariants(variants: Seq[VariantWithContig]): Seq[VariantGroup] = {
    // Group by the key (commonName preferred, rsId fallback, variantId last resort)
    val grouped = variants.groupBy { vwc =>
      vwc.variant.commonName
        .orElse(vwc.variant.rsId)
        .getOrElse(s"variant_${vwc.variant.variantId.getOrElse(0)}")
    }

    grouped.map { case (key, variantsInGroup) =>
      val rsId = variantsInGroup.flatMap(_.variant.rsId).headOption
      val commonName = variantsInGroup.flatMap(_.variant.commonName).headOption

      VariantGroup(
        groupKey = key,
        variants = variantsInGroup,
        rsId = rsId,
        commonName = commonName
      )
    }.toSeq.sortBy(_.displayName)
  }
}
