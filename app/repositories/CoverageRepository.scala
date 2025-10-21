package repositories

import models.domain.genomics.CoverageBenchmark
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.PostgresProfile.api.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for coverage-related database operations.
 *
 * @param ec Execution context for asynchronous operations
 */
@Singleton
class CoverageRepository @Inject()(
                                    override protected val dbConfigProvider: DatabaseConfigProvider
                                  )(using ec: ExecutionContext) extends BaseRepository(dbConfigProvider) {

  /**
   * Retrieves coverage benchmark statistics grouped by lab, test type, and contig.
   *
   * Returns aggregated statistics including mean, min, max values for read length and insert size,
   * along with coverage metrics and their standard deviations for calculating 95% confidence intervals.
   *
   * @return Future containing a sequence of CoverageBenchmark objects
   */
  def getBenchmarkStatistics: Future[Seq[CoverageBenchmark]] = {
    val query = sql"""
      SELECT lab,
             test_type,
             gc.common_name                    as contig,
             avg(read_length)                  as mean_read_len,
             min(read_length)                  as min_read_len,
             max(read_length)                  as max_read_len,
             avg(insert_size)                  as mean_insert_len,
             min(insert_size)                  as min_insert_len,
             max(insert_size)                  as max_insert_len,
             avg(mean_depth)                   as mean_depth_avg,
             stddev(mean_depth)                as mean_depth_stddev,
             avg(bases_no_coverage)            as bases_no_coverage_avg,
             stddev(bases_no_coverage)         as bases_no_coverage_stddev,
             avg(bases_low_quality_mapping)    as bases_low_qual_mapping_avg,
             stddev(bases_low_quality_mapping) as bases_low_qual_mapping_stddev,
             avg(bases_callable)               as bases_callable_avg,
             stddev(bases_callable)            as bases_callable_stddev,
             avg(mean_mapping_quality)         as mean_mapping_quality,
             count(1)                          as num_samples
      FROM alignment_metadata am
      JOIN public.alignment_coverage ac ON am.id = ac.alignment_metadata_id
      JOIN public.genbank_contig gc ON am.genbank_contig_id = gc.genbank_contig_id
      JOIN public.sequence_file sf ON am.sequence_file_id = sf.id
      JOIN public.sequence_library sl ON sl.id = sf.library_id
      GROUP BY lab, test_type, gc.common_name
      ORDER BY lab, test_type, gc.common_name
    """.as[(String, String, String, Option[Double], Option[Int], Option[Int], Option[Double],
      Option[Int], Option[Int], Option[Double], Option[Double], Option[Double],
      Option[Double], Option[Double], Option[Double], Option[Double], Option[Double],
      Option[Double], Int)]

    db.run(query).map { results =>
      results.map { case (lab, testType, contig, meanReadLen, minReadLen, maxReadLen,
      meanInsertLen, minInsertLen, maxInsertLen, meanDepthAvg, meanDepthStddev,
      basesNoCoverageAvg, basesNoCoverageStddev, basesLowQualMappingAvg,
      basesLowQualMappingStddev, basesCallableAvg, basesCallableStddev,
      meanMappingQuality, numSamples) =>
        CoverageBenchmark(
          lab = lab,
          testType = testType,
          contig = contig,
          meanReadLen = meanReadLen,
          minReadLen = minReadLen,
          maxReadLen = maxReadLen,
          meanInsertLen = meanInsertLen,
          minInsertLen = minInsertLen,
          maxInsertLen = maxInsertLen,
          meanDepthAvg = meanDepthAvg,
          meanDepthStddev = meanDepthStddev,
          basesNoCoverageAvg = basesNoCoverageAvg,
          basesNoCoverageStddev = basesNoCoverageStddev,
          basesLowQualMappingAvg = basesLowQualMappingAvg,
          basesLowQualMappingStddev = basesLowQualMappingStddev,
          basesCallableAvg = basesCallableAvg,
          basesCallableStddev = basesCallableStddev,
          meanMappingQuality = meanMappingQuality,
          numSamples = numSamples
        )
      }
    }
  }
}