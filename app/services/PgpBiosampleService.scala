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
                                     specimenDonorRepository: SpecimenDonorRepository
                                   )(implicit ec: ExecutionContext) extends CoordinateValidation {

  def createPgpBiosample(
                          participantId: String,
                          description: String,
                          centerName: String,
                          sex: Option[BiologicalSex] = None,
                          latitude: Option[Double] = None,
                          longitude: Option[Double] = None
                        ): Future[UUID] = {
    val sampleGuid = UUID.randomUUID()

    def createSpecimenDonor(geocoord: Option[Point]) = {
      val donor = SpecimenDonor(
        id = None,
        donorIdentifier = participantId,
        originBiobank = centerName,
        donorType = BiosampleType.PGP,
        sex = sex,
        geocoord = geocoord,
        pgpParticipantId = Some(participantId),
        citizenBiosampleDid = None,
        dateRangeStart = None,
        dateRangeEnd = None
      )
      specimenDonorRepository.create(donor)
    }

    def createBiosample(donorId: Option[Int]) = {
      val metadata = AccessionMetadata(
        pgpParticipantId = Some(participantId),
        citizenBiosampleDid = None
      )

      for {
        accession <- accessionGenerator.generateAccession(BiosampleType.PGP, metadata)
        biosample <- biosampleRepository.create(Biosample(
          id = None,
          sampleGuid = sampleGuid,
          sampleAccession = accession,
          description = description,
          alias = Some(participantId),
          centerName = centerName,
          specimenDonorId = donorId,
          locked = false,
          sourcePlatform = Some("PGP")
        ))
      } yield sampleGuid
    }

    def shouldCreateDonor: Boolean = {
      sex.isDefined || latitude.isDefined || longitude.isDefined
    }

    // First check for existing participant
    biosampleRepository.findByAliasOrAccession(participantId).flatMap {
      case Some((existing, _)) =>
        Future.failed(DuplicateParticipantException(
          s"Participant $participantId already has a biosample with accession ${existing.sampleAccession}"
        ))

      case None =>
        for {
          geocoord <- validateCoordinates(latitude, longitude)
          donorId <- if (shouldCreateDonor) {
            createSpecimenDonor(geocoord).map(donor => Some(donor.id.get))
          } else {
            Future.successful(None)
          }
          guid <- createBiosample(donorId)
        } yield guid
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