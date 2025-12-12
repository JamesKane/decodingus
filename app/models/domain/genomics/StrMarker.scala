package models.domain.genomics

/**
 * Represents a Short Tandem Repeat (STR) marker position on a chromosome.
 * STR markers are used in genetic genealogy for Y-DNA testing.
 *
 * @param id              Optional unique identifier for the STR marker.
 * @param genbankContigId The ID of the associated GenBank contig (chromosome).
 * @param name            Marker name (e.g., "DYS389I", "DYS456").
 * @param startPos        Start position (1-based, inclusive).
 * @param endPos          End position (1-based, inclusive).
 * @param period          Repeat unit length in base pairs.
 * @param verified        Whether the position has been manually verified for this build.
 * @param note            Optional annotation (e.g., "Position estimated via liftover from GRCh38").
 */
case class StrMarker(
  id: Option[Int] = None,
  genbankContigId: Int,
  name: String,
  startPos: Long,
  endPos: Long,
  period: Int,
  verified: Boolean = false,
  note: Option[String] = None
)
