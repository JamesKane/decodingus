package services

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
   * Creates a new biosample and associates additional data, such as publication and sequencing data, with it.
   *
   * @param request The external biosample request carrying metadata about the biosample, its geographical 
   *                coordinates, publication details, and sequencing data.
   * @return A Future containing the unique identifier (UUID) of the created biosample.
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
      _ <- request.publication.map(biosampleDataService.linkPublication(sampleGuid, _))
        .getOrElse(Future.successful(()))
      _ <- biosampleDataService.addSequenceData(sampleGuid, request.sequenceData)
    } yield sampleGuid
  }
}