package services

import jakarta.inject.Inject
import models.api.{PaginatedResult, PublicationWithEnaStudiesAndSampleCount}
import models.domain.publications.Publication
import repositories.{PublicationBiosampleRepository, PublicationRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service that handles business logic related to publications and their associated details.
 *
 * @constructor Creates a new instance of the PublicationService class.
 * @param publicationRepository Repository interface for fetching publication-related data.
 * @param ec                    Implicit execution context for managing asynchronous operations.
 */
class PublicationService @Inject()(publicationRepository: PublicationRepository)(implicit ec: ExecutionContext) {
  /**
   * Retrieves a paginated list of publications along with their associated ENA studies and sample counts.
   *
   * @param page     The page number to retrieve (1-based index).
   * @param pageSize The maximum number of publications to include in each page.
   * @return A Future containing a PaginatedResult with the list of publications, their associated details,
   *         the pagination information, and the total number of publications.
   */
  def getPaginatedPublicationsWithDetails(page: Int, pageSize: Int): Future[PaginatedResult[PublicationWithEnaStudiesAndSampleCount]] = {
    for {
      totalItems <- publicationRepository.countAllPublications()
      publicationsWithDetails <- publicationRepository.findPublicationsWithDetailsPaginated(page, pageSize)
    } yield PaginatedResult(publicationsWithDetails, page, pageSize, totalItems)
  }

  /**
   * Retrieves all publications along with their associated ENA studies and sample counts.
   *
   * This method fetches all records by using a very large page size to ensure all publications are retrieved in one operation.
   *
   * @return A Future containing a sequence of PublicationWithEnaStudiesAndSampleCount objects, 
   *         each representing a publication with its associated ENA studies and sample count.
   */
  def getAllPublicationsWithDetails: Future[Seq[PublicationWithEnaStudiesAndSampleCount]] = {
    publicationRepository.findPublicationsWithDetailsPaginated(1, Int.MaxValue) // Fetch all by using a very large pageSize
  }
  
  def findByDoi(doi: String): Future[Option[Publication]] = {
    publicationRepository.findByDoi(doi)
  }
}
