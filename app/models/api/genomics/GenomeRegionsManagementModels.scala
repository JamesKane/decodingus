package models.api.genomics

import play.api.libs.json.{JsValue, Json, OFormat}

/**
 * API DTOs for Genome Regions Management (CRUD operations).
 */

case class RegionCoordinateDto(
  contig: String,
  start: Long,
  end: Long
)

object RegionCoordinateDto {
  implicit val format: OFormat[RegionCoordinateDto] = Json.format[RegionCoordinateDto]
}

// ============================================================================
// Request DTOs
// ============================================================================

case class CreateGenomeRegionRequest(
  regionType: String,
  name: Option[String] = None,
  coordinates: Map[String, RegionCoordinateDto],
  properties: Option[JsValue] = None
)

object CreateGenomeRegionRequest {
  implicit val format: OFormat[CreateGenomeRegionRequest] = Json.format[CreateGenomeRegionRequest]
}

case class UpdateGenomeRegionRequest(
  regionType: Option[String] = None,
  name: Option[String] = None,
  coordinates: Option[Map[String, RegionCoordinateDto]] = None,
  properties: Option[JsValue] = None
)

object UpdateGenomeRegionRequest {
  implicit val format: OFormat[UpdateGenomeRegionRequest] = Json.format[UpdateGenomeRegionRequest]
}

// ============================================================================
// Bulk Request DTOs
// ============================================================================

case class BulkCreateGenomeRegionsRequest(regions: Seq[CreateGenomeRegionRequest])

object BulkCreateGenomeRegionsRequest {
  implicit val format: OFormat[BulkCreateGenomeRegionsRequest] = Json.format[BulkCreateGenomeRegionsRequest]
}

// ============================================================================
// Response DTOs
// ============================================================================

case class GenomeRegionDetailDto(
  id: Int,
  regionType: String,
  name: Option[String],
  coordinates: Map[String, RegionCoordinateDto],
  properties: JsValue
)

object GenomeRegionDetailDto {
  implicit val format: OFormat[GenomeRegionDetailDto] = Json.format[GenomeRegionDetailDto]
}

// ============================================================================
// List Response DTOs
// ============================================================================

case class GenomeRegionListResponse(
  regions: Seq[GenomeRegionDetailDto],
  total: Int,
  page: Int,
  pageSize: Int
)

object GenomeRegionListResponse {
  implicit val format: OFormat[GenomeRegionListResponse] = Json.format[GenomeRegionListResponse]
}

// ============================================================================
// Bulk Operation Response
// ============================================================================

case class BulkOperationResult(
  index: Int,
  status: String,
  id: Option[Int] = None,
  error: Option[String] = None
)

object BulkOperationResult {
  implicit val format: OFormat[BulkOperationResult] = Json.format[BulkOperationResult]
}

case class BulkOperationResponse(
  total: Int,
  succeeded: Int,
  failed: Int,
  results: Seq[BulkOperationResult]
)

object BulkOperationResponse {
  implicit val format: OFormat[BulkOperationResponse] = Json.format[BulkOperationResponse]
}