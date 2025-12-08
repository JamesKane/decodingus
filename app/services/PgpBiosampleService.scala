package services

import com.vividsolutions.jts.geom.Point
import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.{BiologicalSex, Biosample, BiosampleType, SpecimenDonor}
import repositories.*

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for creating and managing PGP biosamples with associated metadata and data.
 *
 * @constructor Constructs the service with the provided biosample repository and accession generator.
 * @param biosampleRepository Repository interface for managing biosample data.
 * @param accessionGenerator  Generator for creating unique accession numbers for biosamples.
 * @param ec                  Implicit execution context for handling asynchronous operations.
 */
@Singleton
class PgpBiosampleService @Inject()(
                                     biosampleRepository: BiosampleRepository,
                                     accessionGenerator: AccessionNumberGenerator,
                                     biosampleService: BiosampleService
                                   )(implicit ec: ExecutionContext) {

  def createPgpBiosample(
                          participantId: String,
                          description: String,
                          centerName: String,
                          sex: Option[BiologicalSex] = None,
                          latitude: Option[Double] = None,
                          longitude: Option[Double] = None
                        ): Future[UUID] = {
    val sampleGuid = UUID.randomUUID()

    // First check for existing participant
    biosampleRepository.findByAliasOrAccession(participantId).flatMap {
      case Some((existing, _)) =>
        Future.failed(DuplicateParticipantException(
          s"Participant $participantId already has a biosample with accession ${existing.sampleAccession}"
        ))

      case None =>
        for {
          donorId <- {
            val shouldCreatePgpDonor = sex.isDefined || latitude.isDefined || longitude.isDefined
            if (shouldCreatePgpDonor) {
              biosampleService.createOrUpdateSpecimenDonor(
                donorIdentifier = participantId,
                originBiobank = centerName,
                donorType = BiosampleType.PGP,
                sex = sex,
                latitude = latitude,
                longitude = longitude,
                pgpParticipantId = Some(participantId)
              )
            } else {
              Future.successful(None)
            }
          }
          metadata = AccessionMetadata(
            pgpParticipantId = Some(participantId),
            citizenBiosampleDid = None
          )
          accession <- accessionGenerator.generateAccession(BiosampleType.PGP, metadata)
          createdBiosample <- biosampleService.createBiosample(
            sampleGuid = sampleGuid,
            sampleAccession = accession,
            description = description,
            alias = Some(participantId),
            centerName = centerName,
            specimenDonorId = donorId,
            sourcePlatform = Some(utils.GenomicsConstants.PGP_SOURCE_PLATFORM)
          )
        } yield createdBiosample.sampleGuid
    }.recoverWith {
      case e: Exception if e.getMessage.contains("duplicate key") =>
        Future.failed(DuplicateParticipantException(
          s"Participant $participantId already exists (caught at database level)"
        ))
      case e: BiosampleServiceException => Future.failed(e)
      case e: Exception => Future.failed(new RuntimeException(
        s"Failed to create PGP biosample: ${e.getMessage}", e))
    }
  }
}