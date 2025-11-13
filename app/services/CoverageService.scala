package services

import com.google.inject.ImplementedBy
import jakarta.inject.Inject
import models.api.LibraryStatsRequest
import play.api.db.slick.DatabaseConfigProvider
import repositories.{BiosampleRepository, GenbankContigRepository, SequenceFileChecksumRepository, SequenceFileRepository, SequenceLibraryRepository, AlignmentMetadataRepositoryImpl, AlignmentCoverageRepositoryImpl}
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[CoverageServiceImpl])
trait CoverageService {
  def addCoverage(request: LibraryStatsRequest): Future[Unit]
}

class CoverageServiceImpl @Inject()(
                                     biosampleRepository: BiosampleRepository,
                                     sequenceLibraryRepository: SequenceLibraryRepository,
                                     sequenceFileRepository: SequenceFileRepository,
                                     sequenceFileChecksumRepository: SequenceFileChecksumRepository,
                                     alignmentMetadataRepository: AlignmentMetadataRepositoryImpl,
                                     alignmentCoverageRepository: AlignmentCoverageRepositoryImpl,
                                     genbankContigRepository: GenbankContigRepository
                                   )(implicit ec: ExecutionContext) extends CoverageService {

  override def addCoverage(request: LibraryStatsRequest): Future[Unit] = {
    biosampleRepository.findByAliasOrAccession(request.sample_id).flatMap {
      case Some((biosample, _)) =>
        val library = models.domain.genomics.SequenceLibrary(
          id = None,
          sampleGuid = biosample.sampleGuid,
          lab = request.platform,
          testType = "WGS", // Default value
          runDate = LocalDateTime.now(),
          instrument = request.model,
          reads = request.reads.toInt,
          readLength = request.read_len,
          pairedEnd = request.properly_paired_reads > 0,
          insertSize = Some(request.insert_len.toInt),
          created_at = LocalDateTime.now(),
          updated_at = None
        )

        sequenceLibraryRepository.create(library).flatMap { createdLibrary =>
          val fileFutures = request.files.map { fileInfo =>
            val newFile = models.domain.genomics.SequenceFile(
              id = None,
              libraryId = createdLibrary.id.get,
              fileName = fileInfo.fileName,
              fileSizeBytes = fileInfo.fileSizeBytes,
              fileFormat = fileInfo.fileFormat,
              aligner = fileInfo.aligner,
              targetReference = fileInfo.targetReference,
              created_at = LocalDateTime.now(),
              updated_at = None
            )
            sequenceFileRepository.create(newFile).flatMap { createdFile =>
              val checksumFutures = fileInfo.checksums.map { checksumInfo =>
                val checksum = models.domain.genomics.SequenceFileChecksum(
                  id = None,
                  sequenceFileId = createdFile.id.get,
                  checksum = checksumInfo.checksum,
                  algorithm = checksumInfo.algorithm,
                  verifiedAt = LocalDateTime.now()
                )
                sequenceFileChecksumRepository.create(checksum)
              }
              Future.sequence(checksumFutures).flatMap { _ =>
                val contigNames = request.coverage.map(_.contig)
                genbankContigRepository.getByAccessions(contigNames).flatMap { contigs =>
                  val contigMap = contigs.map(c => c.accession -> c.id.get).toMap

                  val coverageFutures = request.coverage.map { stat =>
                    val contigId = contigMap.getOrElse(stat.contig, throw new Exception(s"Contig not found: ${stat.contig}"))

                    val metadata = models.domain.genomics.AlignmentMetadata(
                      id = None,
                      sequenceFileId = createdFile.id.get,
                      genbankContigId = contigId,
                      metricLevel = models.domain.genomics.MetricLevel.CONTIG_OVERALL,
                      analysisTool = fileInfo.aligner,
                      mappedReads = Some(request.mapped_reads),
                      properlyPairedReads = Some(request.properly_paired_reads)
                    )
                    alignmentMetadataRepository.create(metadata).flatMap { createdMetadata =>
                      val coverage = models.domain.genomics.AlignmentCoverage(
                        alignmentMetadataId = createdMetadata.id.get,
                        meanDepth = Some(stat.mean_depth),
                        basesNoCoverage = Some(stat.no_coverage),
                        basesLowQualityMapping = Some(stat.poor_mapping_quality),
                        basesCallable = Some(stat.callable_loci),
                        meanMappingQuality = Some(stat.mean_mapq),
                        numReads = Some(stat.num_reads),
                        covBases = Some(stat.cov_bases),
                        coveragePct = Some(stat.coverage_pct),
                        meanBaseq = Some(stat.mean_baseq),
                        lowCoverage = Some(stat.low_coverage)
                      )
                      alignmentCoverageRepository.create(coverage)
                    }
                  }
                  Future.sequence(coverageFutures)
                }
              }
            }
          }
          Future.sequence(fileFutures).map(_ => ())
        }
      case None =>
        Future.failed(new Exception(s"Biosample not found with sample_id: ${request.sample_id}"))
    }
  }
}
