package services

import com.vividsolutions.jts.geom.Point
import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import models.domain.genomics.{Biosample, BiosampleType}
import repositories.BiosampleRepository
import utils.GeometryUtils

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * ExternalBiosampleService provides functionality for creating a new biosample
 * and associating it with its metadata and sequence data within the system.
 *
 * @constructor Creates an instance of ExternalBiosampleService with the required
 *              dependencies injected.
 * @param biosampleRepository  an instance of BiosampleRepository for managing
 *                             biosample data in the data store.
 * @param biosampleDataService an instance of BiosampleDataService responsible
 *                             for handling biosample-related data operations such
 *                             as linking publications and adding sequence data.
 * @param ec                   an implicit ExecutionContext for handling asynchronous operations.
 */
@Singleton
class ExternalBiosampleService @Inject()(
                                          biosampleRepository: BiosampleRepository,
                                          biosampleDataService: BiosampleDataService
                                        )(implicit ec: ExecutionContext) {

  /**
   * Creates a new biosample record from the provided external biosample request and associates
   * relevant data such as publication and sequencing information. Performs validation for the input data,
   * including geographical coordinates and uniqueness of the sample accession.
   *
   * @param request an instance of ExternalBiosampleRequest containing metadata required to create the biosample,
   *                such as accession, description, coordinates, related publication, and sequencing data.
   * @return a Future containing the UUID of the newly created biosample, or an exception in case of failure.
   */
  def createBiosampleWithData(request: ExternalBiosampleRequest): Future[UUID] = {
    val sampleGuid = UUID.randomUUID()

    def validateCoordinates(lat: Option[Double], lon: Option[Double]): Future[Option[Point]] = {
      (lat, lon) match {
        case (Some(latitude), Some(longitude)) =>
          if (latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180) {
            Future.successful(Some(GeometryUtils.createPoint(latitude, longitude)))
          } else {
            Future.failed(InvalidCoordinatesException(latitude, longitude))
          }
        case (None, None) => Future.successful(None)
        case _ => Future.failed(InvalidCoordinatesException(
          lat.getOrElse(0.0), lon.getOrElse(0.0)
        ))
      }
    }

    def createBiosample(geocoord: Option[Point]) = {
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

      // Check for existing accession first
      biosampleRepository.findByAccession(request.sampleAccession).flatMap {
        case Some(_) => Future.failed(DuplicateAccessionException(request.sampleAccession))
        case None => biosampleRepository.create(biosample)
      }
    }

    def handleDataAssociation(biosample: Biosample) = {
      val publicationFuture = request.publication
        .map(pub => biosampleDataService.linkPublication(sampleGuid, pub)
          .recoverWith { case e =>
            Future.failed(PublicationLinkageException(e.getMessage))
          })
        .getOrElse(Future.successful(()))

      val sequenceDataFuture = biosampleDataService.addSequenceData(sampleGuid, request.sequenceData)
        .recoverWith { case e =>
          Future.failed(SequenceDataValidationException(e.getMessage))
        }

      for {
        _ <- publicationFuture
        _ <- sequenceDataFuture
      } yield sampleGuid
    }

    (for {
      geocoord <- validateCoordinates(request.latitude, request.longitude)
      biosample <- createBiosample(geocoord)
      guid <- handleDataAssociation(biosample)
    } yield guid).recoverWith {
      case e: BiosampleServiceException => Future.failed(e)
      case e: Exception => Future.failed(new RuntimeException(
        s"Failed to create biosample: ${e.getMessage}", e))
    }
  }
}