package models.domain.genomics

import models.dal.domain.genomics.Variant

/**
 * View model that combines a Variant with its associated GenbankContig information.
 * Used for display purposes in the curator interface.
 *
 * @param variant The variant data
 * @param contig  The associated genbank contig (for position context)
 */
case class VariantWithContig(
    variant: Variant,
    contig: GenbankContig
) {
  /**
   * Formats the position as "accession:position" (e.g., "chrY:11912037")
   */
  def formattedPosition: String = s"${contig.commonName.getOrElse(contig.accession)}:${variant.position}"

  /**
   * Gets a short reference genome label (e.g., "GRCh38" from "GRCh38.p14")
   */
  def shortReferenceGenome: String = contig.referenceGenome
    .map(_.split("\\.").head)
    .getOrElse("Unknown")
}
