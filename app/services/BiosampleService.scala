package services

import com.vividsolutions.jts.geom.Point
import jakarta.inject.Inject
import models.domain.genomics.{BiologicalSex, Biosample, BiosampleType, SpecimenDonor}
import repositories.{BiosampleRepository, SpecimenDonorRepository}

import java.util.UUID // Added import
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service that provides operations related to biosamples.
 *
 * This class interfaces with the BiosampleRepository to retrieve biosample data
 * and provides functionality to access biosample information through various operations.
 *
 * @constructor Creates a new instance of the BiosampleService.
 * @param biosampleRepository the repository used to perform operations on biosample data
 */
class BiosampleService @Inject()(
                                  biosampleRepository: BiosampleRepository,
                                  specimenDonorRepository: SpecimenDonorRepository
                                )(implicit ec: ExecutionContext) extends CoordinateValidation {
  /**
   * Retrieves a biosample by its unique identifier.
   *
   * This method interacts with the biosample repository to fetch a biosample
   * entry associated with the given identifier. The result is wrapped in a Future
   * to allow for asynchronous processing.
   *
   * @param id the unique identifier of the biosample to be retrieved
   * @return a Future containing an optional biosample instance; None is returned if no match is found
   */
  def getBiosampleById(id: Int): Future[Option[(Biosample, Option[SpecimenDonor])]] = biosampleRepository.findById(id)

  /**
   * Creates a new SpecimenDonor or returns an existing one if a matching citizen/donorIdentifier is found.
   * This method also handles coordinate validation.
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
                                 ): Future[Option[Int]] = {
    // Determine if a donor should be created based on provided PGP ID or coordinates
    val shouldCreate = pgpParticipantId.isDefined || latitude.isDefined || longitude.isDefined || atUri.isDefined

    if (!shouldCreate) {
      Future.successful(None)
    } else {
      // Check for existing citizen donor
      val existingCitizenDonorFuture = (atUri, Some(donorIdentifier)) match {
        case (Some(did), Some(identifier)) =>
          specimenDonorRepository.findByDidAndIdentifier(did, identifier)
        case _ => Future.successful(None)
      }

      existingCitizenDonorFuture.flatMap {
        case Some(existingDonor) => Future.successful(existingDonor.id)
        case None =>
          validateCoordinates(latitude, longitude).flatMap { (geocoord: Option[Point]) => // Fixed lambda syntax
            val donor = SpecimenDonor(
              id = None,
              donorIdentifier = donorIdentifier,
              originBiobank = originBiobank,
              donorType = donorType,
              sex = sex,
              geocoord = geocoord,
              pgpParticipantId = pgpParticipantId,
              atUri = atUri,
              dateRangeStart = None,
              dateRangeEnd = None
            )
            specimenDonorRepository.create(donor).map(_.id)
          }
      }
    }
  }

  /**
   * Creates a new Biosample record.
   */
  def createBiosample(
                       sampleGuid: UUID,
                       sampleAccession: String,
                       description: String,
                       alias: Option[String],
                       centerName: String,
                       specimenDonorId: Option[Int],
                       sourcePlatform: Option[String]
                     ): Future[Biosample] = {
    val biosample = Biosample(
      id = None,
      sampleGuid = sampleGuid,
      sampleAccession = sampleAccession,
      description = description,
      alias = alias,
      centerName = centerName,
      specimenDonorId = specimenDonorId,
      locked = false,
      sourcePlatform = sourcePlatform
    )
    biosampleRepository.create(biosample)
  }
}

