package services

import jakarta.inject.{Inject, Singleton}
import models.api.{BiosampleUpdate, BiosampleView, BiosampleWithOrigin, ExternalBiosampleRequest, PaginatedResult, PublicationInfo, SampleWithStudies, SequenceDataInfo}
import models.domain.genomics.{BiologicalSex, Biosample, BiosampleType, SpecimenDonor}
import models.domain.publications.PublicationBiosample
import play.api.Logging

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Facade service providing a unified API for all biosample domain operations.
 *
 * This service consolidates operations from multiple specialized biosample services
 * into a single entry point, reducing controller coupling and providing consistent
 * error handling across all biosample operations.
 *
 * Delegates to:
 * - BiosampleService: Core CRUD and specimen donor management
 * - BiosampleUpdateService: Biosample updates with cascading changes
 * - BiosampleDataService: Sequence data and publication linking
 * - BiosamplePublicationService: Publication-biosample associations
 * - BiosampleReportService: Read-only reporting
 * - ExternalBiosampleService: External/citizen biosample workflows
 * - PgpBiosampleService: PGP-specific biosample creation
 */
@Singleton
class BiosampleDomainService @Inject()(
  biosampleService: BiosampleService,
  biosampleUpdateService: BiosampleUpdateService,
  biosampleDataService: BiosampleDataService,
  biosamplePublicationService: BiosamplePublicationService,
  biosampleReportService: BiosampleReportService,
  externalBiosampleService: ExternalBiosampleService,
  pgpBiosampleService: PgpBiosampleService,
  biosampleRepository: repositories.BiosampleRepository
)(implicit ec: ExecutionContext) extends Logging {

  // ==========================================================================
  // Core Operations (from BiosampleService)
  // ==========================================================================

  /**
   * Retrieves a biosample by its unique identifier.
   *
   * @param id the unique identifier of the biosample
   * @return a Future containing an optional tuple of (Biosample, Option[SpecimenDonor])
   */
  def getBiosampleById(id: Int): Future[Option[(Biosample, Option[SpecimenDonor])]] =
    biosampleService.getBiosampleById(id)

  /**
   * Searches for a biosample by alias or accession.
   *
   * @param query the alias or accession to search for
   * @return a Future containing an optional tuple of (Biosample, Option[SpecimenDonor])
   */
  def findByAliasOrAccession(query: String): Future[Option[(Biosample, Option[SpecimenDonor])]] =
    biosampleRepository.findByAliasOrAccession(query)

  /**
   * Retrieves all biosamples with their associated studies.
   *
   * @return a Future containing a sequence of SampleWithStudies
   */
  def findAllWithStudies(): Future[Seq[SampleWithStudies]] =
    biosampleRepository.findAllWithStudies()

  /**
   * Creates a new biosample record.
   *
   * @return the created Biosample
   */
  def createBiosample(
    sampleGuid: UUID,
    sampleAccession: String,
    description: String,
    alias: Option[String],
    centerName: String,
    specimenDonorId: Option[Int],
    sourcePlatform: Option[String]
  ): Future[Biosample] =
    biosampleService.createBiosample(
      sampleGuid, sampleAccession, description, alias, centerName, specimenDonorId, sourcePlatform
    )

  // ==========================================================================
  // Update Operations (from BiosampleUpdateService)
  // ==========================================================================

  /**
   * Updates a biosample with the given modifications.
   * Handles cascading updates to specimen donor and haplogroups.
   *
   * @param id     the unique identifier of the biosample
   * @param update the update object containing field changes
   * @return Either an error message or the updated BiosampleView
   */
  def updateBiosample(id: Int, update: BiosampleUpdate): Future[Either[String, BiosampleView]] =
    biosampleUpdateService.updateBiosample(id, update)

  // ==========================================================================
  // Specimen Donor Operations (from BiosampleService)
  // ==========================================================================

  /**
   * Creates a new SpecimenDonor or returns an existing one if found.
   *
   * @return the donor ID if created/found, None otherwise
   */
  def createOrUpdateSpecimenDonor(
    donorIdentifier: String,
    originBiobank: String,
    donorType: BiosampleType,
    sex: Option[BiologicalSex],
    latitude: Option[Double],
    longitude: Option[Double],
    pgpParticipantId: Option[String] = None,
    atUri: Option[String] = None
  ): Future[Option[Int]] =
    biosampleService.createOrUpdateSpecimenDonor(
      donorIdentifier, originBiobank, donorType, sex, latitude, longitude, pgpParticipantId, atUri
    )

  // ==========================================================================
  // Sequence Data Operations (from BiosampleDataService)
  // ==========================================================================

  /**
   * Adds sequencing data to a sample.
   *
   * @param sampleGuid the unique identifier of the sample
   * @param data       the sequence data information
   */
  def addSequenceData(sampleGuid: UUID, data: SequenceDataInfo): Future[Unit] =
    biosampleDataService.addSequenceData(sampleGuid, data)

  /**
   * Replaces all sequencing data for a sample.
   *
   * @param sampleGuid the unique identifier of the sample
   * @param data       the new sequence data information
   */
  def replaceSequenceData(sampleGuid: UUID, data: SequenceDataInfo): Future[Unit] =
    biosampleDataService.replaceSequenceData(sampleGuid, data)

  // ==========================================================================
  // Publication Operations (from BiosampleDataService and BiosamplePublicationService)
  // ==========================================================================

  /**
   * Associates a publication with a biosample by GUID.
   * Creates the publication if it doesn't exist.
   *
   * @param sampleGuid the GUID of the biosample
   * @param pubInfo    the publication information
   */
  def linkPublication(sampleGuid: UUID, pubInfo: PublicationInfo): Future[Unit] =
    biosampleDataService.linkPublication(sampleGuid, pubInfo)

  /**
   * Links an existing biosample to an existing publication by accession and DOI.
   *
   * @param sampleAccession the accession number of the biosample
   * @param doi             the DOI of the publication
   * @return the created PublicationBiosample association
   */
  def linkBiosampleToPublication(sampleAccession: String, doi: String): Future[PublicationBiosample] =
    biosamplePublicationService.linkBiosampleToPublication(sampleAccession, doi)

  // ==========================================================================
  // Deletion Operations (from BiosampleDataService and ExternalBiosampleService)
  // ==========================================================================

  /**
   * Fully deletes a biosample and all its associated data.
   *
   * @param biosampleId the internal ID of the biosample
   * @param sampleGuid  the GUID of the biosample
   */
  def fullyDeleteBiosampleAndDependencies(biosampleId: Int, sampleGuid: UUID): Future[Unit] =
    biosampleDataService.fullyDeleteBiosampleAndDependencies(biosampleId, sampleGuid)

  /**
   * Deletes a biosample by accession if the citizen DID matches the owner.
   *
   * @param accession  the sample accession
   * @param citizenDid the DID of the requesting citizen
   * @return true if deleted, false if not found or not authorized
   */
  def deleteBiosample(accession: String, citizenDid: String): Future[Boolean] =
    externalBiosampleService.deleteBiosample(accession, citizenDid)

  // ==========================================================================
  // Reporting Operations (from BiosampleReportService)
  // ==========================================================================

  /**
   * Retrieves all biosamples for a publication.
   *
   * @param publicationId the publication ID
   * @return sequence of biosamples with origin information
   */
  def getBiosampleData(publicationId: Int): Future[Seq[BiosampleWithOrigin]] =
    biosampleReportService.getBiosampleData(publicationId)

  /**
   * Retrieves paginated biosamples for a publication.
   *
   * @param publicationId the publication ID
   * @param page          the page number (1-based)
   * @param pageSize      the number of items per page
   * @return paginated result with biosamples
   */
  def getPaginatedBiosampleData(publicationId: Int, page: Int, pageSize: Int): Future[PaginatedResult[BiosampleWithOrigin]] =
    biosampleReportService.getPaginatedBiosampleData(publicationId, page, pageSize)

  // ==========================================================================
  // Specialized Workflows
  // ==========================================================================

  /**
   * Creates an external biosample with all associated data.
   * Handles both creation and update (upsert) patterns.
   *
   * @param request the external biosample request
   * @return the GUID of the created/updated biosample
   */
  def createExternalBiosample(request: ExternalBiosampleRequest): Future[UUID] =
    externalBiosampleService.createBiosampleWithData(request)

  /**
   * Creates a PGP-specific biosample with participant tracking.
   *
   * @param participantId the PGP participant ID
   * @param description   the biosample description
   * @param centerName    the center/biobank name
   * @param sex           optional biological sex
   * @param latitude      optional latitude coordinate
   * @param longitude     optional longitude coordinate
   * @return the GUID of the created biosample
   */
  def createPgpBiosample(
    participantId: String,
    description: String,
    centerName: String,
    sex: Option[BiologicalSex] = None,
    latitude: Option[Double] = None,
    longitude: Option[Double] = None
  ): Future[UUID] =
    pgpBiosampleService.createPgpBiosample(participantId, description, centerName, sex, latitude, longitude)
}
