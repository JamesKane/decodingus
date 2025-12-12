package api

import models.api.genomics.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

/**
 * Tapir endpoint definitions for the Genome Regions API.
 * Provides OpenAPI documentation for reference genome structural annotations.
 */
object GenomeRegionsEndpoints {

  /**
   * List supported genome builds.
   * GET /api/v1/genome-regions
   */
  val listBuilds: PublicEndpoint[Unit, String, SupportedBuildsResponse, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "genome-regions")
      .out(jsonBody[SupportedBuildsResponse])
      .errorOut(stringBody)
      .description("Returns a list of supported reference genome builds and the current data version.")
      .summary("List supported genome builds")
      .tag("Genome Regions")
  }

  /**
   * Get genome regions for a specific build.
   * GET /api/v1/genome-regions/{build}
   */
  val getRegions: PublicEndpoint[String, GenomeRegionsError, GenomeRegionsResponse, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "genome-regions" / path[String]("build")
        .description("Reference genome build (e.g., GRCh38, hg38, GRCh37, hg19, hs1, chm13)"))
      .out(jsonBody[GenomeRegionsResponse])
      .errorOut(
        statusCode(StatusCode.NotFound)
          .and(jsonBody[GenomeRegionsError])
      )
      .description(
        """Returns chromosome region data for a specific reference genome build.
          |
          |**Supported builds:**
          |- GRCh38 (alias: hg38) - NCBI human reference genome assembly
          |- GRCh37 (alias: hg19) - Legacy NCBI assembly
          |- hs1 (aliases: chm13, T2T-CHM13) - Telomere-to-telomere assembly
          |
          |**Response includes:**
          |- Chromosome lengths
          |- Centromere positions
          |- Telomere positions
          |- Cytoband annotations (Giemsa staining)
          |- Y-chromosome specific regions (PAR, XTR, ampliconic, etc.)
          |- STR marker positions with verification status
          |
          |**Caching:**
          |Responses are cached for 7 days. Use the ETag header for conditional requests.
          |""".stripMargin)
      .summary("Get genome regions by build")
      .tag("Genome Regions")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    listBuilds,
    getRegions
  )
}
