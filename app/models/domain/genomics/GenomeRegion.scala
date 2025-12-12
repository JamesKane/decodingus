package models.domain.genomics

/**
 * Represents a structural region within a chromosome, such as centromeres, telomeres,
 * pseudoautosomal regions (PAR), X-transposed regions (XTR), ampliconic regions, etc.
 *
 * @param id              Optional unique identifier for the genome region.
 * @param genbankContigId The ID of the associated GenBank contig (chromosome).
 * @param regionType      The type of region (e.g., "Centromere", "Telomere_P", "PAR1", "XTR").
 * @param name            Optional name for named regions (e.g., "P1" for palindrome 1).
 * @param startPos        Start position (1-based, inclusive).
 * @param endPos          End position (1-based, inclusive).
 * @param modifier        Optional quality modifier (0.1-1.0) indicating confidence in variant calls within this region.
 */
case class GenomeRegion(
  id: Option[Int] = None,
  genbankContigId: Int,
  regionType: String,
  name: Option[String],
  startPos: Long,
  endPos: Long,
  modifier: Option[BigDecimal]
)
