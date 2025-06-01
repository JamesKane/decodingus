package services

import com.vividsolutions.jts.geom.Point
import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.{Biosample, BiosampleType}
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
                                     accessionGenerator: AccessionNumberGenerator
                                   )(implicit ec: ExecutionContext) extends CoordinateValidation {

  /**
   * Creates a new PGP biosample, generates its unique identifier and accession number,
   * and stores it in the biosample repository.
   *
   * @param participantId The unique identifier of the PGP participant.
   * @param sampleDid     The decentralized identifier (DID) of the sample.
   * @param description   A textual description of the biosample.
   * @param centerName    The name of the institution or center managing the biosample.
   * @param sex           An optional field indicating the sex of the biosample.
   * @return A future containing the unique identifier (UUID) of the created biosample.
   */
  def createPgpBiosample(
                          participantId: String,
                          description: String,
                          centerName: String,
                          sex: Option[String] = None,
                          latitude: Option[Double] = None,
                          longitude: Option[Double] = None
                        ): Future[UUID] = {
    val sampleGuid = UUID.randomUUID()

    def createBiosample(geocoord: Option[Point]) = {
      val metadata = AccessionMetadata(
        pgpParticipantId = Some(participantId),
        citizenBiosampleDid = None
      )

      for {
        accession <- accessionGenerator.generateAccession(BiosampleType.PGP, metadata)
        biosample <- biosampleRepository.create(Biosample(
          id = None,
          sampleType = BiosampleType.PGP,
          sampleGuid = sampleGuid,
          sampleAccession = accession,
          description = description,
          alias = Some(participantId),
          centerName = centerName,
          sex = sex,
          geocoord = geocoord,
          specimenDonorId = None,
          pgpParticipantId = Some(participantId),
          citizenBiosampleDid = None,
          sourcePlatform = Some("PGP"),
          dateRangeStart = None,
          dateRangeEnd = None
        ))
      } yield sampleGuid
    }

    // First check for existing participant
    biosampleRepository.findByAliasOrAccession(participantId).flatMap {
      case Some(existing) =>
        Future.failed(DuplicateParticipantException(
          s"Participant $participantId already has a biosample with accession ${existing.sampleAccession}"
        ))

      case None =>
        for {
          geocoord <- validateCoordinates(latitude, longitude)
          guid <- createBiosample(geocoord)
        } yield guid
    }.recoverWith {
      case e: Exception if e.getMessage.contains("duplicate key") =>
        Future.failed(DuplicateParticipantException(
          s"Participant $participantId already exists (caught at database level)"
        ))
    }
  }
}