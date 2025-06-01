package services

import jakarta.inject.Inject
import jakarta.inject.Singleton
import models.api.{ExternalBiosampleRequest, PublicationInfo, SequenceDataInfo}
import models.domain.genomics.{Biosample, BiosampleType, SequenceFile, SequenceFileChecksum, SequenceHttpLocation, SequenceLibrary}
import models.domain.publications.{BiosampleOriginalHaplogroup, Publication, PublicationBiosample}
import repositories.{BiosampleOriginalHaplogroupRepository, BiosampleRepository, PublicationBiosampleRepository, PublicationRepository, SequenceFileChecksumRepository, SequenceFileRepository, SequenceLibraryRepository, SequenceLocationRepository}
import utils.GeometryUtils

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service class responsible for managing external biosample data.
 *
 * This service facilitates the creation, updating, and linking of biosample data
 * with associated sequence data, publications, and other related entities.
 * It handles the persistence and association of complex hierarchical data structures.
 *
 * @param biosampleRepository                   Repository for managing biosample entities.
 * @param sequenceLibraryRepository             Repository for managing sequence library entities.
 * @param sequenceFileRepository                Repository for managing sequence file entities.
 * @param sequenceHttpLocationRepository        Repository for managing sequence HTTP location entities.
 * @param publicationRepository                 Repository for managing publication entities.
 * @param biosampleOriginalHaplogroupRepository Repository for managing biosample original haplogroup entities.
 * @param sequenceFileChecksumRepository        Repository for managing sequence file checksum entities.
 * @param publicationBiosampleRepository        Repository for managing publication-biosample relationships.
 * @param ec                                    Execution context used for executing asynchronous computations.
 */
@Singleton
class ExternalBiosampleService @Inject()(
                                          biosampleRepository: BiosampleRepository,
                                          sequenceLibraryRepository: SequenceLibraryRepository,
                                          sequenceFileRepository: SequenceFileRepository,
                                          sequenceHttpLocationRepository: SequenceLocationRepository[SequenceHttpLocation],
                                          publicationRepository: PublicationRepository,
                                          biosampleOriginalHaplogroupRepository: BiosampleOriginalHaplogroupRepository,
                                          sequenceFileChecksumRepository: SequenceFileChecksumRepository,
                                          publicationBiosampleRepository: PublicationBiosampleRepository
                                        )(implicit ec: ExecutionContext) {

  /**
   * Creates a biosample along with associated sequence data and optional publication information.
   *
   * @param request an instance of `ExternalBiosampleRequest` containing the details of the biosample to be created,
   *                including metadata, geographical coordinates, sequencing data, and optional publication information.
   * @return a `Future` containing the UUID of the created biosample.
   */
  def createBiosampleWithData(request: ExternalBiosampleRequest): Future[UUID] = {
    val sampleGuid = UUID.randomUUID()

    def createBiosample() = {
      val geocoord = (request.latitude, request.longitude) match {
        case (Some(lat), Some(lon)) => Some(GeometryUtils.createPoint(lat, lon))
        case _ => None
      }

      val biosample = Biosample(
        sampleAccession = request.sampleAccession,
        description = request.description,
        alias = request.alias,
        centerName = request.centerName,
        sex = request.sex,
        geocoord = geocoord,
        specimenDonorId = None,
        sampleType = BiosampleType.Standard,
        sampleGuid = sampleGuid
      )
      biosampleRepository.create(biosample)
    }

    for {
      biosample <- createBiosample()
      _ <- request.publication.map(linkPublication(sampleGuid, _)).getOrElse(Future.successful(()))
      _ <- createSequenceData(sampleGuid, request.sequenceData)
    } yield sampleGuid
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
      pairedEnd = false, // This should probably come from the request
      insertSize = None, // This should probably come from the request
      created_at = LocalDateTime.now(),
      updated_at = None
    )

    def createFiles(libraryId: Int) = {
      val files = data.files.map { fileInfo =>
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
          _ <- {
            val httpLocation = SequenceHttpLocation(
              id = None,
              sequenceFileId = createdFile.id.get,
              fileUrl = fileInfo.location.fileUrl,
              fileIndexUrl = fileInfo.location.fileIndexUrl
            )
            sequenceHttpLocationRepository.create(httpLocation)
          }
          _ <- Future.sequence(fileInfo.checksums.map { checksum =>
            val fileChecksum = SequenceFileChecksum(
              id = None,
              sequenceFileId = createdFile.id.get,
              checksum = checksum.checksum,
              algorithm = checksum.algorithm,
              verifiedAt = LocalDateTime.now()
            )
            sequenceFileChecksumRepository.create(fileChecksum)
          })
        } yield ()
      }

      Future.sequence(files).map(_ => ())
    }

    for {
      createdLibrary <- sequenceLibraryRepository.create(library)
      _ <- createFiles(createdLibrary.id.get)
    } yield ()
  }

  /**
   * Adds sequence data associated with a specific sample using its unique identifier.
   *
   * This method facilitates the creation or linkage of sequencing data to a
   * biosample by invoking the underlying `createSequenceData` method.
   *
   * @param sampleGuid the unique identifier of the biosample to which the sequence data will be associated
   * @param data       an instance of `SequenceDataInfo` containing metadata and details about the sequencing data
   * @return a `Future` indicating the completion of the operation, where successful completion yields `Unit`
   */
  def addSequenceData(sampleGuid: UUID, data: SequenceDataInfo): Future[Unit] = {
    createSequenceData(sampleGuid, data)
  }


  /**
   * Links a publication to a biosample by associating their records and creating any additional
   * data relationships, such as original haplogroups, if applicable.
   *
   * @param sampleGuid the unique identifier of the biosample to which the publication will be linked
   * @param pubInfo    an instance of `PublicationInfo` containing the publication details, including DOI,
   *                   PubMed ID, and optional original haplogroup information
   * @return a `Future` indicating the completion of the operation, where successful completion yields `Unit`
   */
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
      _ <- pubInfo.originalHaplogroups match {
        case Some(haplogroupInfo) =>
          biosampleOriginalHaplogroupRepository.create(BiosampleOriginalHaplogroup(
            id = None,
            biosampleId = biosample.id.get,
            publicationId = publication.id.get,
            originalYHaplogroup = haplogroupInfo.yHaplogroup,
            originalMtHaplogroup = haplogroupInfo.mtHaplogroup,
            notes = haplogroupInfo.notes
          ))
        case None => Future.successful(())
      }
    } yield ()
  }
}
