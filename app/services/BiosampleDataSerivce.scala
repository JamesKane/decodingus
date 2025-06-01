package services

import jakarta.inject.{Inject, Singleton}
import models.api.{PublicationInfo, SequenceDataInfo}
import models.domain.genomics.{SequenceFile, SequenceFileChecksum, SequenceHttpLocation, SequenceLibrary}
import models.domain.publications.{BiosampleOriginalHaplogroup, Publication, PublicationBiosample}
import repositories._

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BiosampleDataService @Inject()(
                                      biosampleRepository: BiosampleRepository,
                                      sequenceLibraryRepository: SequenceLibraryRepository,
                                      sequenceFileRepository: SequenceFileRepository,
                                      sequenceHttpLocationRepository: SequenceLocationRepository[SequenceHttpLocation],
                                      publicationRepository: PublicationRepository,
                                      biosampleOriginalHaplogroupRepository: BiosampleOriginalHaplogroupRepository,
                                      sequenceFileChecksumRepository: SequenceFileChecksumRepository,
                                      publicationBiosampleRepository: PublicationBiosampleRepository
                                    )(implicit ec: ExecutionContext) {

  def addSequenceData(sampleGuid: UUID, data: SequenceDataInfo): Future[Unit] = {
    createSequenceData(sampleGuid, data)
  }

  def linkPublication(sampleGuid: UUID, pubInfo: PublicationInfo): Future[Unit] = {
    for {
      maybeBiosample <- biosampleRepository.findByGuid(sampleGuid)
      biosample <- maybeBiosample match {
        case Some(b) => Future.successful(b)
        case None => Future.failed(new IllegalArgumentException(s"Biosample not found for GUID: $sampleGuid"))
      }
      publication <- publicationRepository.savePublication(Publication(
        id = None,
        openAlexId = None,
        pubmedId = pubInfo.pubmedId,
        doi = pubInfo.doi,
        title = pubInfo.doi.map(d => s"Publication with DOI: $d").getOrElse("Unknown publication"),
        authors = None,
        abstractSummary = None,
        journal = None,
        publicationDate = None,
        url = None,
        citationNormalizedPercentile = None,
        citedByCount = None,
        openAccessStatus = None,
        openAccessUrl = None,
        primaryTopic = None,
        publicationType = None,
        publisher = None
      ))
      _ <- publicationBiosampleRepository.create(PublicationBiosample(
        publicationId = publication.id.get,
        biosampleId = biosample.id.get
      ))
      _ <- pubInfo.originalHaplogroups.map { haplogroupInfo =>
        biosampleOriginalHaplogroupRepository.create(BiosampleOriginalHaplogroup(
          id = None,
          biosampleId = biosample.id.get,
          publicationId = publication.id.get,
          originalYHaplogroup = haplogroupInfo.yHaplogroup,
          originalMtHaplogroup = haplogroupInfo.mtHaplogroup,
          notes = haplogroupInfo.notes
        ))
      }.getOrElse(Future.successful(()))
    } yield ()
  }

  private def createSequenceData(sampleGuid: UUID, data: SequenceDataInfo): Future[Unit] = {
    val library = SequenceLibrary(
      id = None,
      sampleGuid = sampleGuid,
      lab = data.platformName,
      testType = data.testType,
      runDate = LocalDateTime.now(),
      instrument = data.platformName,
      reads = data.reads.getOrElse(0),
      readLength = data.readLength.getOrElse(0),
      pairedEnd = false,
      insertSize = None,
      created_at = LocalDateTime.now(),
      updated_at = None
    )

    def createFiles(libraryId: Int): Future[Unit] = {
      val fileCreations = data.files.map { fileInfo =>
        val file = SequenceFile(
          id = None,
          libraryId = libraryId,
          fileName = fileInfo.fileName,
          fileSizeBytes = fileInfo.fileSizeBytes,
          fileFormat = fileInfo.fileFormat,
          aligner = fileInfo.aligner,
          targetReference = fileInfo.targetReference,
          created_at = LocalDateTime.now(),
          updated_at = None
        )

        for {
          createdFile <- sequenceFileRepository.create(file)
          _ <- sequenceHttpLocationRepository.create(SequenceHttpLocation(
            id = None,
            sequenceFileId = createdFile.id.get,
            fileUrl = fileInfo.location.fileUrl,
            fileIndexUrl = fileInfo.location.fileIndexUrl
          ))
          _ <- Future.sequence(fileInfo.checksums.map { checksum =>
            sequenceFileChecksumRepository.create(SequenceFileChecksum(
              id = None,
              sequenceFileId = createdFile.id.get,
              checksum = checksum.checksum,
              algorithm = checksum.algorithm,
              verifiedAt = LocalDateTime.now()
            ))
          })
        } yield ()
      }

      Future.sequence(fileCreations).map(_ => ())
    }

    for {
      createdLibrary <- sequenceLibraryRepository.create(library)
      _ <- createFiles(createdLibrary.id.get)
    } yield ()
  }
}