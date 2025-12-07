package services

import jakarta.inject.{Inject, Singleton}
import models.api.{PublicationInfo, SequenceDataInfo}
import models.domain.genomics.{SequenceFile, SequenceFileChecksum, SequenceHttpLocation, SequenceLibrary}
import models.domain.publications.{BiosampleOriginalHaplogroup, Publication, PublicationBiosample}
import repositories._

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service class for managing biosample data, sequence data, and their associations with publications.
 * This class interacts with various repository interfaces to perform database operations
 * including the creation, association, and linking of biosample-related data.
 *
 * @constructor Creates an instance of the BiosampleDataService class.
 * @param biosampleRepository                   Repository for managing biosample entities.
 * @param sequenceLibraryRepository             Repository for managing sequence libraries.
 * @param sequenceFileRepository                Repository for managing sequence files.
 * @param sequenceHttpLocationRepository        Repository for managing sequence file HTTP locations.
 * @param publicationRepository                 Repository for managing publication entities.
 * @param biosampleOriginalHaplogroupRepository Repository for managing the original haplogroup information associated with biosamples.
 * @param sequenceFileChecksumRepository        Repository for managing sequence file checksums.
 * @param publicationBiosampleRepository        Repository for managing associations between publications and biosamples.
 * @param ec                                    Execution context for handling asynchronous operations.
 */
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

  /**
   * Adds sequencing data to a specific sample identified by its unique GUID.
   *
   * This method accepts metadata and related information about the sequencing data,
   * encapsulated within the `SequenceDataInfo` object, and associates it with the specified sample.
   *
   * @param sampleGuid The unique identifier of the sample to which the sequencing data will be added.
   * @param data       Metadata and details about the sequencing data, provided as a `SequenceDataInfo` object.
   * @return A `Future` representing the asynchronous completion of the operation. The `Future` resolves to `Unit` if the operation succeeds, or it may fail with an exception if unsuccessful
   *
   */
  def addSequenceData(sampleGuid: UUID, data: SequenceDataInfo): Future[Unit] = {
    createSequenceData(sampleGuid, data)
  }

  /**
   * Replaces the sequencing data for a specific sample.
   *
   * This method first removes all existing sequencing libraries and their associated files
   * for the given sample GUID, and then adds the new sequencing data.
   *
   * @param sampleGuid The unique identifier of the sample to update.
   * @param data       The new metadata and details about the sequencing data.
   * @return A `Future` representing the asynchronous completion of the operation.
   */
  def replaceSequenceData(sampleGuid: UUID, data: SequenceDataInfo): Future[Unit] = {
    for {
      // 1. Find all existing libraries
      libraries <- sequenceLibraryRepository.findBySampleGuid(sampleGuid)

      // 2. Delete files for each library
      _ <- Future.sequence(libraries.map { lib =>
        sequenceFileRepository.deleteByLibraryId(lib.id.get)
      })

      // 3. Delete the libraries themselves
      _ <- Future.sequence(libraries.map { lib =>
        sequenceLibraryRepository.delete(lib.id.get)
      })

      // 4. Create new sequence data
      _ <- createSequenceData(sampleGuid, data)
    } yield ()
  }

  /**
   * Associates a publication with a specific biosample identified by its unique GUID. If the publication
   * does not already exist in the repository, it is created. Optionally, original haplogroup information
   * associated with the publication may also be stored for the biosample.
   *
   * @param sampleGuid The unique identifier (GUID) of the biosample to link the publication with.
   * @param pubInfo    The publication information, encapsulated in a `PublicationInfo` instance, which
   *                   includes optional identifiers (e.g., DOI, PubMed ID) and haplogroup data.
   * @return A `Future` representing the asynchronous operation. The `Future` resolves to `Unit` if the
   *         operation completes successfully, or fails with an exception if an error occurs.
   */
  def linkPublication(sampleGuid: UUID, pubInfo: PublicationInfo): Future[Unit] = {
    for {
      maybeBiosampleWithDonor <- biosampleRepository.findByGuid(sampleGuid)
      (biosample, _) <- maybeBiosampleWithDonor match {
        case Some(b) => Future.successful(b)
        case None => Future.failed(new IllegalArgumentException(s"Biosample not found for GUID: $sampleGuid"))
      }
      // First try to find existing publication by DOI
      maybePublication <- pubInfo.doi.map(doi =>
        publicationRepository.findByDoi(doi)
      ).getOrElse(Future.successful(None))
      // Use existing or create new publication
      publication <- maybePublication match {
        case Some(pub) => Future.successful(pub)
        case None => publicationRepository.savePublication(Publication(
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
      }
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


  /**
   * Fully deletes a biosample and all its associated data (publication links,
   * original haplogroups, sequence libraries, sequence files, file locations, and checksums).
   *
   * @param biosampleId The internal ID of the biosample to delete.
   * @param sampleGuid  The GUID of the biosample to delete.
   * @return A `Future` that completes when all associated data and the biosample itself have been deleted.
   */
  def fullyDeleteBiosampleAndDependencies(biosampleId: Int, sampleGuid: UUID): Future[Unit] = {
    for {
      // 1. Delete associated publication links
      _ <- publicationBiosampleRepository.deleteByBiosampleId(biosampleId)
      // 2. Delete associated original haplogroup records
      _ <- biosampleOriginalHaplogroupRepository.deleteByBiosampleId(biosampleId)
      // 3. Find and delete all sequence libraries and their files
      libraries <- sequenceLibraryRepository.findBySampleGuid(sampleGuid)
      _ <- Future.sequence(libraries.map { lib =>
        for {
          _ <- sequenceFileRepository.deleteByLibraryId(lib.id.get) // Deletes files, locations, checksums (if cascading)
          _ <- sequenceLibraryRepository.delete(lib.id.get) // Deletes the library
        } yield ()
      })
      // 4. Delete the biosample itself
      _ <- biosampleRepository.delete(biosampleId)
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