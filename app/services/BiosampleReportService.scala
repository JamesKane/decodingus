package services

import models.api.{BiosampleWithOrigin, PaginatedResult}
import repositories.BiosampleRepository

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class BiosampleReportService @Inject(biosampleRepository: BiosampleRepository)(implicit ec: ExecutionContext) {
  def getBiosampleData(publicationId: Int): Future[Seq[BiosampleWithOrigin]] =
    biosampleRepository.findBiosamplesWithOriginForPublication(publicationId)

  def getPaginatedBiosampleData(publicationId: Int, page: Int, pageSize: Int): Future[PaginatedResult[BiosampleWithOrigin]] = {
    for {
      totalItems <- biosampleRepository.countBiosamplesForPublication(publicationId)
      items <- biosampleRepository.findPaginatedBiosamplesWithOriginForPublication(publicationId, page, pageSize)
    } yield PaginatedResult(items, page, pageSize, totalItems)
  }
}
