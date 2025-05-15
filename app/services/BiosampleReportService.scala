package services

import models.api.BiosampleWithOrigin
import repositories.BiosampleRepository

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class BiosampleReportService @Inject(biosampleRepository: BiosampleRepository)(implicit ec: ExecutionContext) {
  def getBiosampleData(publicationId: Int): Future[Seq[BiosampleWithOrigin]] =
    biosampleRepository.findBiosamplesWithOriginForPublication(publicationId)
}
