package services

import models.api.{BiosampleWithOrigin, PaginatedResult}
import repositories.BiosampleRepository

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service responsible for providing biosample-related data and reports.
 *
 * @param biosampleRepository the repository used for retrieving biosample data
 * @param ec                  the execution context in which the service operates
 */
class BiosampleReportService @Inject(biosampleRepository: BiosampleRepository)(implicit ec: ExecutionContext) {
  /**
   * Retrieves all biosamples with their origin metadata associated with a specific publication.
   *
   * @param publicationId the unique identifier of the publication for which biosamples are being queried
   * @return a future containing a sequence of biosamples with their origin information
   */
  def getBiosampleData(publicationId: Int): Future[Seq[BiosampleWithOrigin]] =
    biosampleRepository.findBiosamplesWithOriginForPublication(publicationId)

  /**
   * Retrieves a paginated list of biosamples with origin metadata associated with a specific publication.
   * This method calculates the total number of biosamples, fetches the requested page of biosample data,
   * and constructs a paginated result containing the items and metadata such as current page, page size, and total item count.
   *
   * @param publicationId the unique identifier of the publication for which biosamples are being queried
   * @param page          the page number to retrieve, starting from 1
   * @param pageSize      the number of items to include on each page
   * @return a future containing a `PaginatedResult` object, which includes the current page of biosample data,
   *         the page number, page size, and the total number of biosamples
   */
  def getPaginatedBiosampleData(publicationId: Int, page: Int, pageSize: Int): Future[PaginatedResult[BiosampleWithOrigin]] = {
    for {
      totalItems <- biosampleRepository.countBiosamplesForPublication(publicationId)
      items <- biosampleRepository.findPaginatedBiosamplesWithOriginForPublication(publicationId, page, pageSize)
    } yield PaginatedResult(items, page, pageSize, totalItems)
  }
}
