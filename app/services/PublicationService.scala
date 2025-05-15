package services

import jakarta.inject.Inject
import models.api.{PaginatedResult, PublicationWithEnaStudiesAndSampleCount}
import repositories.{PublicationBiosampleRepository, PublicationRepository}

import scala.concurrent.{ExecutionContext, Future}

class PublicationService @Inject()(publicationRepository: PublicationRepository)(implicit ec: ExecutionContext) {
  def getPaginatedPublicationsWithDetails(page: Int, pageSize: Int): Future[PaginatedResult[PublicationWithEnaStudiesAndSampleCount]] = {
    for {
      totalItems <- publicationRepository.countAllPublications()
      publicationsWithDetails <- publicationRepository.findPublicationsWithDetailsPaginated(page, pageSize)
    } yield PaginatedResult(publicationsWithDetails, page, pageSize, totalItems)
  }

  def getAllPublicationsWithDetails(): Future[Seq[PublicationWithEnaStudiesAndSampleCount]] = {
    publicationRepository.findPublicationsWithDetailsPaginated(1, Int.MaxValue) // Fetch all by using a very large pageSize
  }
}
