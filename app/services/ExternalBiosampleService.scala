package services

import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import models.domain.genomics.{Biosample, BiosampleType}
import repositories.BiosampleRepository
import utils.GeometryUtils

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ExternalBiosampleService @Inject()(
                                          biosampleRepository: BiosampleRepository,
                                          biosampleDataService: BiosampleDataService
                                        )(implicit ec: ExecutionContext) {

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