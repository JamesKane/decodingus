package services

import jakarta.inject.Inject
import models.api.PublicationWithEnaStudiesAndSampleCount
import repositories.{PublicationBiosampleRepository, PublicationRepository}

import scala.concurrent.{ExecutionContext, Future}


class PublicationService @Inject()(
                                    publicationRepository: PublicationRepository,
                                    publicationBiosampleRepository: PublicationBiosampleRepository
                                  )(implicit ec: ExecutionContext) {

  def getAllPublicationsWithDetails(): Future[Seq[PublicationWithEnaStudiesAndSampleCount]] = {
    publicationRepository.getAllPublications().flatMap { publications =>
      Future.sequence(publications.map { publication =>
        val enaStudiesFuture = publicationRepository.getEnaStudiesForPublication(publication.id.getOrElse(-1)) // Assuming Publication has an ID
        val sampleCountFuture = publicationBiosampleRepository.countSamplesForPublication(publication.id.getOrElse(-1))

        for {
          enaStudies <- enaStudiesFuture
          sampleCount <- sampleCountFuture
        } yield PublicationWithEnaStudiesAndSampleCount(publication, enaStudies, sampleCount)
      })
    }
  }
}
