package models.domain.genomics

/**
 * Represents a cytoband annotation for chromosome ideogram display.
 * Cytobands are banding patterns visible under microscopy after Giemsa staining.
 *
 * @param id              Optional unique identifier for the cytoband.
 * @param genbankContigId The ID of the associated GenBank contig (chromosome).
 * @param name            Band name (e.g., "p11.32", "q11.21").
 * @param startPos        Start position (1-based, inclusive).
 * @param endPos          End position (1-based, inclusive).
 * @param stain           Giemsa stain pattern: gneg, gpos25, gpos50, gpos75, gpos100, acen, gvar, stalk.
 */
case class Cytoband(
  id: Option[Int] = None,
  genbankContigId: Int,
  name: String,
  startPos: Long,
  endPos: Long,
  stain: String
)
