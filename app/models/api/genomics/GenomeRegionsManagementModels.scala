package models.api.genomics

import play.api.libs.json.{Json, OFormat, Reads}

/**
 * API DTOs for Genome Regions Management (CRUD operations).
 */

// ============================================================================
// Request DTOs
// ============================================================================

case class CreateGenomeRegionRequest(
  genbankContigId: Int,
  regionType: String,
  name: Option[String] = None,
  startPos: Long,
  endPos: Long,
  modifier: Option[BigDecimal] = None
)

object CreateGenomeRegionRequest {
  implicit val format: OFormat[CreateGenomeRegionRequest] = Json.format[CreateGenomeRegionRequest]
}

case class UpdateGenomeRegionRequest(
  regionType: Option[String] = None,
  name: Option[String] = None,
  startPos: Option[Long] = None,
  endPos: Option[Long] = None,
  modifier: Option[BigDecimal] = None
)

object UpdateGenomeRegionRequest {
  implicit val format: OFormat[UpdateGenomeRegionRequest] = Json.format[UpdateGenomeRegionRequest]
}

case class CreateCytobandRequest(
  genbankContigId: Int,
  name: String,
  startPos: Long,
  endPos: Long,
  stain: String
)

object CreateCytobandRequest {
  implicit val format: OFormat[CreateCytobandRequest] = Json.format[CreateCytobandRequest]
}

case class UpdateCytobandRequest(
  name: Option[String] = None,
  startPos: Option[Long] = None,
  endPos: Option[Long] = None,
  stain: Option[String] = None
)

object UpdateCytobandRequest {
  implicit val format: OFormat[UpdateCytobandRequest] = Json.format[UpdateCytobandRequest]
}

case class CreateStrMarkerRequest(
  genbankContigId: Int,
  name: String,
  startPos: Long,
  endPos: Long,
  period: Int,
  verified: Boolean = false,
  note: Option[String] = None
)

object CreateStrMarkerRequest {
  implicit val format: OFormat[CreateStrMarkerRequest] = Json.format[CreateStrMarkerRequest]
}

case class UpdateStrMarkerRequest(
  name: Option[String] = None,
  startPos: Option[Long] = None,
  endPos: Option[Long] = None,
  period: Option[Int] = None,
  verified: Option[Boolean] = None,
  note: Option[String] = None
)

object UpdateStrMarkerRequest {
  implicit val format: OFormat[UpdateStrMarkerRequest] = Json.format[UpdateStrMarkerRequest]
}

// ============================================================================
// Bulk Request DTOs
// ============================================================================

case class BulkCreateGenomeRegionsRequest(regions: Seq[CreateGenomeRegionRequest])

object BulkCreateGenomeRegionsRequest {
  implicit val format: OFormat[BulkCreateGenomeRegionsRequest] = Json.format[BulkCreateGenomeRegionsRequest]
}

case class BulkCreateCytobandsRequest(cytobands: Seq[CreateCytobandRequest])

object BulkCreateCytobandsRequest {
  implicit val format: OFormat[BulkCreateCytobandsRequest] = Json.format[BulkCreateCytobandsRequest]
}

case class BulkCreateStrMarkersRequest(markers: Seq[CreateStrMarkerRequest])

object BulkCreateStrMarkersRequest {
  implicit val format: OFormat[BulkCreateStrMarkersRequest] = Json.format[BulkCreateStrMarkersRequest]
}

// ============================================================================
// Response DTOs (with additional contig info)
// ============================================================================

case class GenomeRegionDetailDto(
  id: Int,
  genbankContigId: Int,
  contigName: Option[String],
  referenceGenome: Option[String],
  regionType: String,
  name: Option[String],
  startPos: Long,
  endPos: Long,
  modifier: Option[BigDecimal]
)

object GenomeRegionDetailDto {
  implicit val format: OFormat[GenomeRegionDetailDto] = Json.format[GenomeRegionDetailDto]
}

case class CytobandDetailDto(
  id: Int,
  genbankContigId: Int,
  contigName: Option[String],
  referenceGenome: Option[String],
  name: String,
  startPos: Long,
  endPos: Long,
  stain: String
)

object CytobandDetailDto {
  implicit val format: OFormat[CytobandDetailDto] = Json.format[CytobandDetailDto]
}

case class StrMarkerDetailDto(
  id: Int,
  genbankContigId: Int,
  contigName: Option[String],
  referenceGenome: Option[String],
  name: String,
  startPos: Long,
  endPos: Long,
  period: Int,
  verified: Boolean,
  note: Option[String]
)

object StrMarkerDetailDto {
  implicit val format: OFormat[StrMarkerDetailDto] = Json.format[StrMarkerDetailDto]
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

case class CytobandListResponse(
  cytobands: Seq[CytobandDetailDto],
  total: Int,
  page: Int,
  pageSize: Int
)

object CytobandListResponse {
  implicit val format: OFormat[CytobandListResponse] = Json.format[CytobandListResponse]
}

case class StrMarkerListResponse(
  markers: Seq[StrMarkerDetailDto],
  total: Int,
  page: Int,
  pageSize: Int
)

object StrMarkerListResponse {
  implicit val format: OFormat[StrMarkerListResponse] = Json.format[StrMarkerListResponse]
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
