package services

import jakarta.inject.Inject
import models.domain.Biosample
import repositories.BiosampleRepository

import scala.concurrent.Future

/**
 * Service that provides operations related to biosamples.
 *
 * This class interfaces with the BiosampleRepository to retrieve biosample data
 * and provides functionality to access biosample information through various operations.
 *
 * @constructor Creates a new instance of the BiosampleService.
 * @param biosampleRepository the repository used to perform operations on biosample data
 */
class BiosampleService @Inject()(biosampleRepository: BiosampleRepository) {
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
  def getBiosampleById(id: Int): Future[Option[Biosample]] = biosampleRepository.findById(id)
}
