package models.domain.genomics

import play.api.libs.json.{Format, JsValue, Json}

/**
 * Coordinate information for a specific reference genome build.
 */
case class RegionCoordinate(
  contig: String,
  start: Long,
  end: Long
)

object RegionCoordinate {
  implicit val format: Format[RegionCoordinate] = Json.format[RegionCoordinate]
}

/**
 * Represents a structural region within a chromosome (or a cytoband).
 * Supports multi-reference coordinates.
 *
 * @param id          Optional unique identifier (region_id).
 * @param regionType  The type of region (e.g., "Centromere", "Cytoband", "PAR1").
 * @param name        Optional name (e.g., "p11.32" for cytobands, "P1" for palindromes).
 * @param coordinates Map of BuildName -> Coordinate (e.g., "GRCh38" -> {contig: "chrY", start: ...}).
 * @param properties  Additional properties as JSON (e.g., {"stain": "gpos75", "modifier": 0.5}).
 */
case class GenomeRegion(
  id: Option[Int] = None,
  regionType: String,
  name: Option[String],
  coordinates: Map[String, RegionCoordinate],
  properties: JsValue
)

object GenomeRegion {
  implicit val format: Format[GenomeRegion] = Json.format[GenomeRegion]
}