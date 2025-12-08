package repositories

import models.dal.DatabaseSchema
import models.domain.genomics.{CoverageBenchmark, SequencingLab}
import play.api.db.slick.DatabaseConfigProvider
import models.dal.MyPostgresProfile.api.*

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
      mapStatistics(results)
    }
  }

  /**
   * Retrieves a list of all sequencing laboratories, sorted by name.
   *
   * @return A Future containing a sequence of SequencingLab objects, each representing a laboratory with its details.
   */
  def getAllLabs: Future[Seq[SequencingLab]] = db.run {
    DatabaseSchema.domain.genomics.sequencingLabs
      .sortBy(_.name)
      .result
      .map(_.map { row =>
        SequencingLab(
          id = row.id,
          name = row.name,
          isD2c = row.isD2c,
          websiteUrl = row.websiteUrl,
          descriptionMarkdown = row.descriptionMarkdown
        )
      })
  }

  /**
   * Retrieves coverage benchmark statistics for a specific laboratory.
   *
   * The method queries the database to fetch aggregated statistics grouped by laboratory name,
   * test type, and contig. Results include metrics such as mean, min, and max values for
   * read length and insert size, along with coverage-related metrics and their statistical
   * standard deviations.
   *
   * @param labId The unique identifier of the sequencing laboratory.
   * @return A Future containing a sequence of CoverageBenchmark objects, each representing
   *         the aggregated benchmark data for the specified laboratory.
   */
  def getBenchmarksByLab(labId: Int): Future[Seq[CoverageBenchmark]] = {
    val query = sql"""
      SELECT sl.name                                  as lab,
             sl2.test_type,
             gc.common_name                           as contig,
             AVG(sl2.read_length)                     as mean_read_len,
             MIN(sl2.read_length)                     as min_read_len,
             MAX(sl2.read_length)                     as max_read_len,
             AVG(sl2.insert_size)                     as mean_insert_len,
             MIN(sl2.insert_size)                     as min_insert_len,
             MAX(sl2.insert_size)                     as max_insert_len,
             AVG(ac.mean_depth)                       as mean_depth_avg,
             STDDEV_POP(ac.mean_depth)                as mean_depth_stddev,
             AVG(ac.bases_no_coverage)                as bases_no_coverage_avg,
             STDDEV_POP(ac.bases_no_coverage)         as bases_no_coverage_stddev,
             AVG(ac.bases_low_quality_mapping)        as bases_low_qual_mapping_avg,
             STDDEV_POP(ac.bases_low_quality_mapping) as bases_low_qual_mapping_stddev,
             AVG(ac.bases_callable)                   as bases_callable_avg,
             STDDEV_POP(ac.bases_callable)            as bases_callable_stddev,
             AVG(ac.mean_mapping_quality)             as mean_mapping_quality,
             COUNT(DISTINCT sl.id)                    as num_samples
      FROM alignment_coverage ac
               JOIN alignment_metadata am ON ac.alignment_metadata_id = am.id
               JOIN sequence_file sf ON sf.id = am.sequence_file_id
               JOIN sequence_library sl2 ON sl2.id = sf.library_id
               JOIN sequencing_lab sl ON sl2.lab = sl.name
               JOIN genbank_contig gc ON am.genbank_contig_id = gc.genbank_contig_id
      WHERE sl.id = $labId
      GROUP BY sl.name, sl2.test_type, gc.common_name, gc.genbank_contig_id
      ORDER BY sl.name, sl2.test_type, gc.genbank_contig_id
    """.as[(String, String, String, Option[Double], Option[Int], Option[Int], Option[Double],
      Option[Int], Option[Int], Option[Double], Option[Double], Option[Double],
      Option[Double], Option[Double], Option[Double], Option[Double], Option[Double],
      Option[Double], Int)]

    db.run(query).map { results =>
      mapStatistics(results)
    }
  }

  /**
   * Maps a sequence of statistical results into a sequence of CoverageBenchmark objects.
   *
   * This method takes raw statistical data tuples grouped by laboratory, test type, and contig,
   * and transforms them into CoverageBenchmark objects. Each tuple contains various metrics
   * such as mean, min, and max values for read length and insert size, as well as coverage-related
   * statistics and their standard deviations.
   *
   * @param results A vector of tuples where each tuple represents statistical data. Each tuple
   *                consists of the following elements:
   *                - lab: The laboratory name (String).
   *                - testType: The test type identifier (String).
   *                - contig: The genomic contig identifier (String).
   *                - meanReadLen: Optional mean read length (Option[Double]).
   *                - minReadLen: Optional minimum read length (Option[Int]).
   *                - maxReadLen: Optional maximum read length (Option[Int]).
   *                - meanInsertLen: Optional mean insert length (Option[Double]).
   *                - minInsertLen: Optional minimum insert length (Option[Int]).
   *                - maxInsertLen: Optional maximum insert length (Option[Int]).
   *                - meanDepthAvg: Optional average mean depth (Option[Double]).
   *                - meanDepthStddev: Optional standard deviation of mean depth (Option[Double]).
   *                - basesNoCoverageAvg: Optional average number of bases with no coverage (Option[Double]).
   *                - basesNoCoverageStddev: Optional standard deviation of bases with no coverage (Option[Double]).
   *                - basesLowQualMappingAvg: Optional average number of bases with low-quality mapping (Option[Double]).
   *                - basesLowQualMappingStddev: Optional standard deviation of bases with low-quality mapping (Option[Double]).
   *                - basesCallableAvg: Optional average number of callable bases (Option[Double]).
   *                - basesCallableStddev: Optional standard deviation of callable bases (Option[Double]).
   *                - meanMappingQuality: Optional mean mapping quality (Option[Double]).
   *                - numSamples: The number of samples included in the statistics (Int).
   */
  private def mapStatistics(results: Vector[(String, String, String, Option[Double], Option[Int], Option[Int], Option[Double], Option[Int], Option[Int], Option[Double], Option[Double], Option[Double], Option[Double], Option[Double], Option[Double], Option[Double], Option[Double], Option[Double], Int)]) = {
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