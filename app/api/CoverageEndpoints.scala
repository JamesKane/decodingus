package api

import models.domain.genomics.CoverageBenchmark
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

/**
 * Defines API endpoints for coverage-related operations.
 *
 * This object provides Tapir endpoint definitions for retrieving coverage benchmark statistics.
 */
object CoverageEndpoints {

  /**
   * Endpoint for retrieving coverage benchmark statistics grouped by lab, test type, and contig.
   *
   * Returns aggregated statistics including mean, min, max values for read length and insert size,
   * along with coverage metrics and their standard deviations for calculating 95% confidence intervals.
   */
  private val getBenchmarks: PublicEndpoint[Unit, String, List[CoverageBenchmark], Any] = {
    endpoint
      .get
      .in("api" / "v1" / "coverage" / "benchmarks")
      .out(jsonBody[List[CoverageBenchmark]])
      .errorOut(stringBody)
      .description("Returns aggregated coverage benchmark statistics grouped by lab, test type, and contig. " +
        "Standard deviation values are provided to calculate 95% confidence intervals when there is more than one sample in the group.")
      .summary("Get coverage benchmark statistics")
      .tag("Coverage")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    getBenchmarks
  )
}