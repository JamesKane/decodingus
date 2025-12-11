package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Request to add a reference build for an existing variant.
 * Matches on commonName or rsId to find the variant group,
 * then adds the new build coordinates.
 *
 * @param name         The variant name (commonName) to match
 * @param rsId         Optional rsId to match (used if name not found)
 * @param contig       The contig name (e.g., "chrY")
 * @param position     The position on the contig
 * @param refAllele    The reference allele
 * @param altAllele    The alternate allele
 * @param refGenome    The reference genome (e.g., "GRCh37.p13", "T2T-CHM13v2.0")
 * @param variantType  The variant type (e.g., "SNP", "INDEL")
 */
case class AddVariantBuildRequest(
  name: Option[String],
  rsId: Option[String],
  contig: String,
  position: Int,
  refAllele: String,
  altAllele: String,
  refGenome: String,
  variantType: String = "SNP"
)

object AddVariantBuildRequest {
  implicit val format: OFormat[AddVariantBuildRequest] = Json.format[AddVariantBuildRequest]
}

/**
 * Bulk request to add builds to multiple variants.
 */
case class BulkAddVariantBuildsRequest(
  variants: Seq[AddVariantBuildRequest]
)

object BulkAddVariantBuildsRequest {
  implicit val format: OFormat[BulkAddVariantBuildsRequest] = Json.format[BulkAddVariantBuildsRequest]
}

/**
 * Request to update the rsId of a variant.
 *
 * @param name  The variant name (commonName) to match
 * @param rsId  The rsId to set
 */
case class UpdateVariantRsIdRequest(
  name: String,
  rsId: String
)

object UpdateVariantRsIdRequest {
  implicit val format: OFormat[UpdateVariantRsIdRequest] = Json.format[UpdateVariantRsIdRequest]
}

/**
 * Bulk request to update rsIds for multiple variants.
 */
case class BulkUpdateRsIdsRequest(
  variants: Seq[UpdateVariantRsIdRequest]
)

object BulkUpdateRsIdsRequest {
  implicit val format: OFormat[BulkUpdateRsIdsRequest] = Json.format[BulkUpdateRsIdsRequest]
}

/**
 * Result of a single variant operation.
 */
case class VariantOperationResult(
  name: Option[String],
  rsId: Option[String],
  status: String,
  message: Option[String] = None,
  variantId: Option[Int] = None
)

object VariantOperationResult {
  implicit val format: OFormat[VariantOperationResult] = Json.format[VariantOperationResult]
}

/**
 * Response for bulk variant operations.
 */
case class BulkVariantOperationResponse(
  total: Int,
  succeeded: Int,
  failed: Int,
  results: Seq[VariantOperationResult]
)

object BulkVariantOperationResponse {
  implicit val format: OFormat[BulkVariantOperationResponse] = Json.format[BulkVariantOperationResponse]
}

// ============================================================================
// Alias Source Management Models
// ============================================================================

/**
 * Request to update the source for aliases matching a prefix pattern.
 *
 * @param aliasPrefix  The prefix to match (e.g., "FGC" matches "FGC29071")
 * @param newSource    The new source value (e.g., "FGC")
 * @param oldSource    Optional: only update aliases with this current source (e.g., "migration")
 */
case class UpdateAliasSourceRequest(
  aliasPrefix: String,
  newSource: String,
  oldSource: Option[String] = None
)

object UpdateAliasSourceRequest {
  implicit val format: OFormat[UpdateAliasSourceRequest] = Json.format[UpdateAliasSourceRequest]
}

/**
 * Bulk request to update sources for multiple prefix patterns.
 */
case class BulkUpdateAliasSourcesRequest(
  updates: Seq[UpdateAliasSourceRequest]
)

object BulkUpdateAliasSourcesRequest {
  implicit val format: OFormat[BulkUpdateAliasSourcesRequest] = Json.format[BulkUpdateAliasSourcesRequest]
}

/**
 * Result of a single alias source update operation.
 */
case class AliasSourceUpdateResult(
  aliasPrefix: String,
  newSource: String,
  aliasesUpdated: Int,
  status: String,
  message: Option[String] = None
)

object AliasSourceUpdateResult {
  implicit val format: OFormat[AliasSourceUpdateResult] = Json.format[AliasSourceUpdateResult]
}

/**
 * Response for bulk alias source update operations.
 */
case class BulkAliasSourceUpdateResponse(
  total: Int,
  totalAliasesUpdated: Int,
  results: Seq[AliasSourceUpdateResult]
)

object BulkAliasSourceUpdateResponse {
  implicit val format: OFormat[BulkAliasSourceUpdateResponse] = Json.format[BulkAliasSourceUpdateResponse]
}

/**
 * Summary of alias sources in the database.
 */
case class AliasSourceSummary(
  source: String,
  count: Int
)

object AliasSourceSummary {
  implicit val format: OFormat[AliasSourceSummary] = Json.format[AliasSourceSummary]
}

/**
 * Response for alias source statistics.
 */
case class AliasSourceStatsResponse(
  sources: Seq[AliasSourceSummary],
  totalAliases: Int
)

object AliasSourceStatsResponse {
  implicit val format: OFormat[AliasSourceStatsResponse] = Json.format[AliasSourceStatsResponse]
}
