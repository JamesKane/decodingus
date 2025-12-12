package models.api.genomics

import play.api.libs.json.{Json, OFormat}

/**
 * API DTOs for the Genome Regions API.
 * These models match the specification for the Navigator client.
 */

/**
 * A genomic position range.
 */
case class RegionDto(
  start: Long,
  end: Long,
  `type`: Option[String] = None,
  modifier: Option[Double] = None
)

object RegionDto {
  implicit val format: OFormat[RegionDto] = Json.format[RegionDto]
}

/**
 * A named genomic region (e.g., palindrome P1).
 */
case class NamedRegionDto(
  name: String,
  start: Long,
  end: Long,
  `type`: String,
  modifier: Option[Double] = None
)

object NamedRegionDto {
  implicit val format: OFormat[NamedRegionDto] = Json.format[NamedRegionDto]
}

/**
 * Telomere regions at chromosome ends.
 */
case class TelomeresDto(
  p: Option[RegionDto] = None,
  q: Option[RegionDto] = None
)

object TelomeresDto {
  implicit val format: OFormat[TelomeresDto] = Json.format[TelomeresDto]
}

/**
 * Cytoband annotation for ideogram display.
 */
case class CytobandDto(
  name: String,
  start: Long,
  end: Long,
  stain: String
)

object CytobandDto {
  implicit val format: OFormat[CytobandDto] = Json.format[CytobandDto]
}

/**
 * STR marker position.
 */
case class StrMarkerDto(
  name: String,
  start: Long,
  end: Long,
  period: Int,
  verified: Boolean,
  note: Option[String] = None
)

object StrMarkerDto {
  implicit val format: OFormat[StrMarkerDto] = Json.format[StrMarkerDto]
}

/**
 * Y-chromosome specific regions grouped by type.
 */
case class YChromosomeRegionsDto(
  par1: Option[RegionDto] = None,
  par2: Option[RegionDto] = None,
  xtr: Seq[RegionDto] = Seq.empty,
  ampliconic: Seq[RegionDto] = Seq.empty,
  palindromes: Seq[NamedRegionDto] = Seq.empty,
  heterochromatin: Option[RegionDto] = None,
  xDegenerate: Seq[RegionDto] = Seq.empty
)

object YChromosomeRegionsDto {
  implicit val format: OFormat[YChromosomeRegionsDto] = Json.format[YChromosomeRegionsDto]
}

/**
 * All region data for a single chromosome.
 */
case class ChromosomeRegionsDto(
  length: Long,
  centromere: Option[RegionDto] = None,
  telomeres: Option[TelomeresDto] = None,
  cytobands: Seq[CytobandDto] = Seq.empty,
  regions: Option[YChromosomeRegionsDto] = None,
  strMarkers: Seq[StrMarkerDto] = Seq.empty
)

object ChromosomeRegionsDto {
  implicit val format: OFormat[ChromosomeRegionsDto] = Json.format[ChromosomeRegionsDto]
}

/**
 * Complete response for the genome regions API.
 */
case class GenomeRegionsResponse(
  build: String,
  version: String,
  generatedAt: String,
  chromosomes: Map[String, ChromosomeRegionsDto]
)

object GenomeRegionsResponse {
  implicit val format: OFormat[GenomeRegionsResponse] = Json.format[GenomeRegionsResponse]
}

/**
 * Error response for unknown builds or other failures.
 */
case class GenomeRegionsError(
  error: String,
  message: String,
  supportedBuilds: Seq[String]
)

object GenomeRegionsError {
  implicit val format: OFormat[GenomeRegionsError] = Json.format[GenomeRegionsError]
}

/**
 * Response for listing supported builds.
 */
case class SupportedBuildsResponse(
  supportedBuilds: Seq[String],
  version: String
)

object SupportedBuildsResponse {
  implicit val format: OFormat[SupportedBuildsResponse] = Json.format[SupportedBuildsResponse]
}
