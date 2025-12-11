package api

import models.api.*
import services.ExportMetadata
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

/**
 * Tapir endpoint definitions for the public Variant API.
 *
 * Provides searchable access to the variant database with pagination.
 * Response format is designed to be forward-compatible with the
 * proposed variant_v2 schema (see variant-schema-simplification.md).
 */
object VariantEndpoints {

  /**
   * Search variants by name, rsId, or alias.
   *
   * GET /api/v1/variants?query=M269&page=1&pageSize=25
   */
  val searchVariants: PublicEndpoint[(Option[String], Int, Int), String, VariantSearchResponse, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "variants")
      .in(query[Option[String]]("query").description("Search term (name, rsId, or alias). Leave empty to browse all."))
      .in(query[Int]("page").default(1).description("Page number (1-based)"))
      .in(query[Int]("pageSize").default(25).validate(Validator.inRange(1, 100)).description("Items per page (max 100)"))
      .out(jsonBody[VariantSearchResponse])
      .errorOut(stringBody)
      .description(
        """Search the variant database by name, rsId, or alias.
          |
          |Results include:
          |- Canonical name (primary identifier)
          |- Coordinates across reference assemblies (GRCh37, GRCh38, hs1)
          |- Per-assembly alleles (handles strand differences between assemblies)
          |- All known aliases grouped by source
          |- Defining haplogroup (if this variant defines a branch)
          |
          |**Note**: The same variant name may appear multiple times if it represents
          |parallel mutations in different lineages (e.g., L21 in R1b vs L21 in I2).
          |""".stripMargin)
      .summary("Search variants")
      .tag("Variants")
  }

  /**
   * Get a single variant by ID.
   *
   * GET /api/v1/variants/:id
   */
  val getVariantById: PublicEndpoint[Int, String, PublicVariantDTO, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "variants" / path[Int]("variantId").description("Variant ID"))
      .out(jsonBody[PublicVariantDTO])
      .errorOut(stringBody)
      .description("Get detailed information for a specific variant by its ID.")
      .summary("Get variant by ID")
      .tag("Variants")
  }

  /**
   * Get all variants defining a specific haplogroup.
   *
   * GET /api/v1/haplogroups/:name/variants
   */
  val getVariantsByHaplogroup: PublicEndpoint[String, String, Seq[PublicVariantDTO], Any] = {
    endpoint
      .get
      .in("api" / "v1" / "haplogroups" / path[String]("haplogroupName").description("Haplogroup name (e.g., R-M269)") / "variants")
      .out(jsonBody[Seq[PublicVariantDTO]])
      .errorOut(stringBody)
      .description("Get all variants that define a specific haplogroup.")
      .summary("Get variants by haplogroup")
      .tag("Variants")
  }

  /**
   * Download full variant export (gzipped JSONL).
   *
   * GET /api/v1/variants/export
   *
   * Note: This endpoint returns a binary file download, not JSON.
   */
  val downloadExport: PublicEndpoint[Unit, String, String, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "variants" / "export")
      .out(header[String]("Content-Type"))
      .errorOut(stringBody)
      .description(
        """Download the complete variant database as a gzipped JSONL file.
          |
          |This export is regenerated daily at 4 AM UTC and includes all variants
          |in the full PublicVariantDTO format. The file is typically 100-200MB compressed.
          |
          |Each line is a JSON object representing one variant group.
          |
          |**Response:** Binary file (application/gzip)
          |
          |**Use cases:**
          |- Edge App novel variant annotation
          |- Offline analysis
          |- Data synchronization
          |""".stripMargin)
      .summary("Download variant export")
      .tag("Variants")
  }

  /**
   * Get metadata about the current export file.
   *
   * GET /api/v1/variants/export/metadata
   */
  val exportMetadata: PublicEndpoint[Unit, String, ExportMetadata, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "variants" / "export" / "metadata")
      .out(jsonBody[ExportMetadata])
      .errorOut(stringBody)
      .description("Get metadata about the current variant export file, including generation time and variant count.")
      .summary("Get export metadata")
      .tag("Variants")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    searchVariants,
    getVariantById,
    getVariantsByHaplogroup,
    downloadExport,
    exportMetadata
  )
}
