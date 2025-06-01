package services

import jakarta.inject.{Inject, Singleton}
import models.domain.publications.PublicationBiosample
import repositories.{BiosampleRepository, PublicationBiosampleRepository, PublicationRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service responsible for managing the association between biosamples and publications.
 * This class provides functionality to link biosamples with publications based on their unique identifiers.
 *
 * @constructor Creates a new instance of `BiosamplePublicationService` and injects the required repositories.
 * @param biosampleRepository            Repository for handling operations related to biosamples.
 * @param publicationRepository          Repository for handling operations related to publications.
 * @param publicationBiosampleRepository Repository for managing associations between publications and biosamples.
 * @param ec                             Implicit `ExecutionContext` used for asynchronous operations.
 */
@Singleton
class BiosamplePublicationService @Inject()(
                                             biosampleRepository: BiosampleRepository,
                                             publicationRepository: PublicationRepository,
                                             publicationBiosampleRepository: PublicationBiosampleRepository
                                           )(implicit ec: ExecutionContext) {

  /**
   * Links a biosample to a publication by their respective identifiers.
   *
   * @param sampleAccession The accession number of the biosample to be linked.
   * @param doi             The DOI (Digital Object Identifier) of the publication to be linked.
   *                        The method will clean the DOI if it contains a URL.
   * @return A `Future` containing the `PublicationBiosample` object that represents the association
   *         between the specified biosample and publication.
   *         The future will fail if the biosample or publication is not found, or if there is an issue
   *         creating the association.
   */
  def linkBiosampleToPublication(sampleAccession: String, doi: String): Future[PublicationBiosample] = {
    def cleanDoi(input: String): String = input.trim match {
      case url if url.startsWith("https://doi.org/") => url.substring("https://doi.org/".length)
      case url if url.startsWith("http://doi.org/") => url.substring("http://doi.org/".length)
      case doi => doi
    }

    for {
      biosample <- biosampleRepository.findByAccession(sampleAccession).flatMap {
        case Some(sample) => Future.successful(sample)
        case None => Future.failed(new IllegalArgumentException(s"Biosample with accession $sampleAccession not found"))
      }

      publication <- publicationRepository.findByDoi(cleanDoi(doi)).flatMap {
        case Some(pub) => Future.successful(pub)
        case None => Future.failed(new IllegalArgumentException(s"Publication with DOI $doi not found"))
      }

      link <- publicationBiosampleRepository.create(
        PublicationBiosample(
          publicationId = publication.id.getOrElse(
            throw new IllegalStateException("Publication ID is missing")
          ),
          biosampleId = biosample.id.getOrElse(
            throw new IllegalStateException("Biosample ID is missing")
          )
        )
      )
    } yield link
  }
}