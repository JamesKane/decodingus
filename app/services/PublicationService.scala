package services

import actors.PublicationUpdateActor.UpdateSinglePublication
import jakarta.inject.Inject
import models.api.{PaginatedResult, PublicationWithEnaStudiesAndSampleCount}
import models.domain.publications.Publication
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import repositories.PublicationRepository

import javax.inject.Named
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service that handles business logic related to publications and their associated details.
 *
 * @constructor Creates a new instance of the PublicationService class.
 * @param publicationRepository Repository interface for fetching publication-related data.
 * @param ec                    Implicit execution context for managing asynchronous operations.
 */
class PublicationService @Inject()(
                                    publicationRepository: PublicationRepository,
                                    @Named("publication-update-actor") publicationUpdateActor: ActorRef
                                  )(implicit ec: ExecutionContext) {
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

  /**
   * Processes a publication by DOI, optionally forcing a refresh of the data.
   *
   * @param doi The DOI of the publication to process
   * @param forceRefresh Whether to force a refresh of the publication data
   * @return A Future containing an Option[Publication]
   */
  def processPublication(doi: String, forceRefresh: Boolean): Future[Option[Publication]] = {
    if (forceRefresh) {
      // Use the actor to force refresh
      implicit val timeout: Timeout = Timeout(30.seconds)
      (publicationUpdateActor ? UpdateSinglePublication(doi))
        .mapTo[Option[Publication]]
    } else {
      // First try to find existing publication
      publicationRepository.findByDoi(doi).flatMap {
        case Some(pub) => Future.successful(Some(pub))
        case None =>
          // If not found, fetch it fresh
          implicit val timeout: Timeout = Timeout(30.seconds)
          (publicationUpdateActor ? UpdateSinglePublication(doi))
            .mapTo[Option[Publication]]
      }
    }
  }

}
