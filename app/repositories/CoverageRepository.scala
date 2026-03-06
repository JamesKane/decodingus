package repositories

import models.dal.DatabaseSchema
import models.domain.genomics.{CoverageBenchmark, SequencingLab}
import play.api.db.slick.DatabaseConfigProvider
import models.dal.MyPostgresProfile.api.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for coverage-related database operations.
 * Coverage data is now embedded as JSONB on alignment_metadata.
 */
@Singleton
class CoverageRepository @Inject()(
                                    override protected val dbConfigProvider: DatabaseConfigProvider
                                  )(using ec: ExecutionContext) extends BaseRepository(dbConfigProvider) {

  def getBenchmarkStatistics: Future[Seq[CoverageBenchmark]] = {
    val query = sql"""
      SELECT lab,
             ttd.code                                                    as test_type,
             gc.common_name                                              as contig,
             avg(read_length)                                            as mean_read_len,
             min(read_length)                                            as min_read_len,
             max(read_length)                                            as max_read_len,
             avg(insert_size)                                            as mean_insert_len,
             min(insert_size)                                            as min_insert_len,
             max(insert_size)                                            as max_insert_len,
             avg((am.coverage->>'meanDepth')::double precision)          as mean_depth_avg,
             stddev((am.coverage->>'meanDepth')::double precision)       as mean_depth_stddev,
             avg((am.coverage->>'basesNoCoverage')::double precision)    as bases_no_coverage_avg,
             stddev((am.coverage->>'basesNoCoverage')::double precision) as bases_no_coverage_stddev,
             avg((am.coverage->>'basesLowQualityMapping')::double precision)    as bases_low_qual_mapping_avg,
             stddev((am.coverage->>'basesLowQualityMapping')::double precision) as bases_low_qual_mapping_stddev,
             avg((am.coverage->>'basesCallable')::double precision)      as bases_callable_avg,
             stddev((am.coverage->>'basesCallable')::double precision)   as bases_callable_stddev,
             avg((am.coverage->>'meanMappingQuality')::double precision) as mean_mapping_quality,
             count(1)                                                    as num_samples
      FROM alignment_metadata am
      JOIN public.genbank_contig gc ON am.genbank_contig_id = gc.genbank_contig_id
      JOIN public.sequence_file sf ON am.sequence_file_id = sf.id
      JOIN public.sequence_library sl ON sl.id = sf.library_id
      JOIN public.test_type_definition ttd ON sl.test_type_id = ttd.id
      WHERE am.coverage IS NOT NULL
      GROUP BY lab, ttd.code, gc.common_name
      ORDER BY lab, ttd.code, gc.common_name
    """.as[(String, String, String, Option[Double], Option[Int], Option[Int], Option[Double],
      Option[Int], Option[Int], Option[Double], Option[Double], Option[Double],
      Option[Double], Option[Double], Option[Double], Option[Double], Option[Double],
      Option[Double], Int)]

    db.run(query).map { results =>
      mapStatistics(results)
    }
  }

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

  def getBenchmarksByLab(labId: Int): Future[Seq[CoverageBenchmark]] = {
    val query = sql"""
      SELECT sl.name                                                             as lab,
             ttd.code                                                            as test_type,
             gc.common_name                                                      as contig,
             AVG(sl2.read_length)                                                as mean_read_len,
             MIN(sl2.read_length)                                                as min_read_len,
             MAX(sl2.read_length)                                                as max_read_len,
             AVG(sl2.insert_size)                                                as mean_insert_len,
             MIN(sl2.insert_size)                                                as min_insert_len,
             MAX(sl2.insert_size)                                                as max_insert_len,
             AVG((am.coverage->>'meanDepth')::double precision)                  as mean_depth_avg,
             STDDEV_POP((am.coverage->>'meanDepth')::double precision)           as mean_depth_stddev,
             AVG((am.coverage->>'basesNoCoverage')::double precision)            as bases_no_coverage_avg,
             STDDEV_POP((am.coverage->>'basesNoCoverage')::double precision)     as bases_no_coverage_stddev,
             AVG((am.coverage->>'basesLowQualityMapping')::double precision)     as bases_low_qual_mapping_avg,
             STDDEV_POP((am.coverage->>'basesLowQualityMapping')::double precision) as bases_low_qual_mapping_stddev,
             AVG((am.coverage->>'basesCallable')::double precision)              as bases_callable_avg,
             STDDEV_POP((am.coverage->>'basesCallable')::double precision)       as bases_callable_stddev,
             AVG((am.coverage->>'meanMappingQuality')::double precision)         as mean_mapping_quality,
             COUNT(DISTINCT sl.id)                                               as num_samples
      FROM alignment_metadata am
               JOIN sequence_file sf ON sf.id = am.sequence_file_id
               JOIN sequence_library sl2 ON sl2.id = sf.library_id
               JOIN test_type_definition ttd ON sl2.test_type_id = ttd.id
               JOIN sequencing_lab sl ON sl2.lab = sl.name
               JOIN genbank_contig gc ON am.genbank_contig_id = gc.genbank_contig_id
      WHERE sl.id = $labId AND am.coverage IS NOT NULL
      GROUP BY sl.name, ttd.code, gc.common_name, gc.genbank_contig_id
      ORDER BY sl.name, ttd.code, gc.genbank_contig_id
    """.as[(String, String, String, Option[Double], Option[Int], Option[Int], Option[Double],
      Option[Int], Option[Int], Option[Double], Option[Double], Option[Double],
      Option[Double], Option[Double], Option[Double], Option[Double], Option[Double],
      Option[Double], Int)]

    db.run(query).map { results =>
      mapStatistics(results)
    }
  }

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
