package models.domain.genomics

/**
 * Represents aggregated coverage benchmark data grouped by lab, test type, and contig.
 *
 * @param lab                       Laboratory name
 * @param testType                  Type of test performed
 * @param contig                    Common name of the contig
 * @param meanReadLen               Average read length
 * @param minReadLen                Minimum read length
 * @param maxReadLen                Maximum read length
 * @param meanInsertLen             Average insert size
 * @param minInsertLen              Minimum insert size
 * @param maxInsertLen              Maximum insert size
 * @param meanDepthAvg              Average of mean depth values
 * @param meanDepthStddev           Standard deviation of mean depth (for 95% CI)
 * @param basesNoCoverageAvg        Average of bases with no coverage
 * @param basesNoCoverageStddev     Standard deviation of bases with no coverage (for 95% CI)
 * @param basesLowQualMappingAvg    Average of bases with low quality mapping
 * @param basesLowQualMappingStddev Standard deviation of bases with low quality mapping (for 95% CI)
 * @param basesCallableAvg          Average of callable bases
 * @param basesCallableStddev       Standard deviation of callable bases (for 95% CI)
 * @param meanMappingQuality        Average mapping quality
 * @param numSamples                Number of samples in the group
 */
case class CoverageBenchmark(
                              lab: String,
                              testType: String,
                              contig: String,
                              meanReadLen: Option[Double],
                              minReadLen: Option[Int],
                              maxReadLen: Option[Int],
                              meanInsertLen: Option[Double],
                              minInsertLen: Option[Int],
                              maxInsertLen: Option[Int],
                              meanDepthAvg: Option[Double],
                              meanDepthStddev: Option[Double],
                              basesNoCoverageAvg: Option[Double],
                              basesNoCoverageStddev: Option[Double],
                              basesLowQualMappingAvg: Option[Double],
                              basesLowQualMappingStddev: Option[Double],
                              basesCallableAvg: Option[Double],
                              basesCallableStddev: Option[Double],
                              meanMappingQuality: Option[Double],
                              numSamples: Int
                            )

object CoverageBenchmark {

  import play.api.libs.json.*

  implicit val coverageBenchmarkFormat: Format[CoverageBenchmark] = Json.format[CoverageBenchmark]
}