package repositories

import jakarta.inject.Inject
import models.api.PublicationWithEnaStudiesAndSampleCount
import models.dal.DatabaseSchema
import models.domain.publications.{EnaStudy, Publication}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

/**
 * Represents a repository interface for handling operations related to publications and their associated data.
 */
trait PublicationRepository {
  /**
   * Fetches all publications available in the repository.
   *
   * @return a Future containing a sequence of Publication objects.
   */
  def getAllPublications: Future[Seq[Publication]]

  /**
   * Retrieves a sequence of EnaStudy records associated with a specific publication.
   *
   * @param publicationId the unique identifier of the publication for which associated EnaStudy records are to be fetched
   * @return a Future containing a sequence of EnaStudy objects related to the specified publication
   */
  def getEnaStudiesForPublication(publicationId: Int): Future[Seq[EnaStudy]]

  /**
   * Retrieves a paginated list of publications along with associated ENA studies and their sample counts.
   *
   * @param page     the page number to retrieve (1-based index)
   * @param pageSize the number of records to include in each page
   * @return a Future containing a sequence of PublicationWithEnaStudiesAndSampleCount objects
   */
  def findPublicationsWithDetailsPaginated(page: Int, pageSize: Int): Future[Seq[PublicationWithEnaStudiesAndSampleCount]]

  /**
   * Counts the total number of publications available in the repository.
   *
   * @return a Future containing the total count of publications as a Long
   */
  def countAllPublications(): Future[Long]
}

class PublicationRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationRepository with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api.*

  private val publications = DatabaseSchema.domain.publications.publications
  private val publicationEnaStudies = DatabaseSchema.domain.publications.publicationEnaStudies
  private val enaStudies = DatabaseSchema.domain.publications.enaStudies
  private val publicationBiosamples = DatabaseSchema.domain.publications.publicationBiosamples

  override def getAllPublications: Future[Seq[Publication]] = db.run(publications.result)

  override def getEnaStudiesForPublication(publicationId: Int): Future[Seq[EnaStudy]] = {
    val query = for {
      pes <- publicationEnaStudies if pes.publicationId === publicationId
      es <- enaStudies if es.id === pes.enaStudyId
    } yield es
    db.run(query.result)
  }

  override def findPublicationsWithDetailsPaginated(page: Int, pageSize: Int): Future[Seq[PublicationWithEnaStudiesAndSampleCount]] = {
    val offset = (page - 1) * pageSize
    val query = publications.drop(offset).take(pageSize)

    db.run(query.result).flatMap { paginatedPublications =>
      Future.sequence(paginatedPublications.map { publication =>
        val enaStudyQuery = (for {
          pes <- publicationEnaStudies if pes.publicationId === publication.id
          es <- enaStudies if es.id === pes.enaStudyId
        } yield es).result

        val sampleCountQuery = publicationBiosamples.filter(_.publicationId === publication.id).length.result

        for {
          enaStudiesResult <- db.run(enaStudyQuery)
          sampleCountResult <- db.run(sampleCountQuery)
        } yield PublicationWithEnaStudiesAndSampleCount(publication, enaStudiesResult, sampleCountResult)
      })
    }
  }

  override def countAllPublications(): Future[Long] = {
    db.run(publications.length.result.map(_.toLong))
  }
}