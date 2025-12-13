package models.domain.haplogroups

import play.api.libs.json.{Json, OFormat, Format, Reads, Writes}

import java.time.LocalDateTime

/**
 * Tracks the provenance of a haplogroup node and its variants from multiple sources.
 *
 * Credit assignment follows a tiered model:
 * - ISOGG credit is preserved on existing nodes (authoritative backbone)
 * - Incoming sources get credit for new splits and terminal branches they contribute
 *
 * @param primaryCredit     Source with primary discovery credit for this node
 * @param nodeProvenance    All sources that have contributed to this node's existence
 * @param variantProvenance Per-variant source attribution (variant name -> set of sources)
 * @param lastMergedAt      Timestamp of the most recent merge operation affecting this node
 * @param lastMergedFrom    Source of the most recent merge operation
 */
case class HaplogroupProvenance(
  primaryCredit: String,
  nodeProvenance: Set[String] = Set.empty,
  variantProvenance: Map[String, Set[String]] = Map.empty,
  lastMergedAt: Option[LocalDateTime] = None,
  lastMergedFrom: Option[String] = None
) {

  /**
   * Add a source to nodeProvenance.
   */
  def addNodeSource(source: String): HaplogroupProvenance =
    copy(nodeProvenance = nodeProvenance + source)

  /**
   * Add a source attribution for a specific variant.
   */
  def addVariantSource(variantName: String, source: String): HaplogroupProvenance =
    copy(variantProvenance = variantProvenance.updatedWith(variantName) {
      case Some(sources) => Some(sources + source)
      case None => Some(Set(source))
    })

  /**
   * Merge another provenance record into this one, combining all sources.
   */
  def merge(other: HaplogroupProvenance): HaplogroupProvenance = {
    val mergedVariants = (variantProvenance.keySet ++ other.variantProvenance.keySet).map { key =>
      key -> (variantProvenance.getOrElse(key, Set.empty) ++ other.variantProvenance.getOrElse(key, Set.empty))
    }.toMap

    HaplogroupProvenance(
      primaryCredit = this.primaryCredit, // Preserve existing primary credit
      nodeProvenance = nodeProvenance ++ other.nodeProvenance,
      variantProvenance = mergedVariants,
      lastMergedAt = Seq(lastMergedAt, other.lastMergedAt).flatten.maxOption,
      lastMergedFrom = other.lastMergedFrom.orElse(lastMergedFrom)
    )
  }

  /**
   * Update merge timestamp and source.
   */
  def withMergeInfo(source: String, timestamp: LocalDateTime): HaplogroupProvenance =
    copy(lastMergedAt = Some(timestamp), lastMergedFrom = Some(source))
}

object HaplogroupProvenance {
  // Custom JSON format to handle Set[String] and Map[String, Set[String]]
  implicit val setStringFormat: Format[Set[String]] = Format(
    Reads.seq[String].map(_.toSet),
    Writes.seq[String].contramap(_.toSeq)
  )

  implicit val mapStringSetFormat: Format[Map[String, Set[String]]] = Format(
    Reads.map[Set[String]],
    Writes.map[Set[String]]
  )

  implicit val format: OFormat[HaplogroupProvenance] = Json.format[HaplogroupProvenance]

  val empty: HaplogroupProvenance = HaplogroupProvenance(primaryCredit = "")

  /**
   * Create initial provenance for a new node from a source.
   */
  def forNewNode(source: String, variants: Seq[String] = Seq.empty): HaplogroupProvenance = {
    val variantProv = variants.map(v => v -> Set(source)).toMap
    HaplogroupProvenance(
      primaryCredit = source,
      nodeProvenance = Set(source),
      variantProvenance = variantProv,
      lastMergedAt = Some(LocalDateTime.now()),
      lastMergedFrom = Some(source)
    )
  }

  /**
   * Determine if ISOGG credit should be preserved (returns true if existing credit is ISOGG).
   */
  def shouldPreserveCredit(existingCredit: String): Boolean =
    existingCredit.equalsIgnoreCase("ISOGG")
}
