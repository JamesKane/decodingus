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

  /**
   * Retrieves all DOIs of existing publications in the repository.
   *
   * @return A Future containing a sequence of DOIs (Strings).
   */
  def getAllDois: Future[Seq[String]]

  def findByDoi(doi: String): Future[Option[Publication]]

  /**
   * Saves a publication to the database. If a publication with the same OpenAlex ID or DOI
   * already exists, it updates the existing record; otherwise, it inserts a new one.
   *
   * @param publication The publication to save or update.
   * @return A Future containing the saved or updated Publication object (with its database ID).
   */
  def savePublication(publication: Publication): Future[Publication]
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

    // Apply sorting first, then pagination
    val sortedAndPaginatedQuery = publications
      .sortBy { p =>
        // Sort in descending order by citation percentile, then cited by count, then publication date
        // Use .desc for descending.
        // For Option columns, .desc.nullsLast or .desc.nullsFirst determines how NULLs are sorted.
        // If higher percentile/count is better, and no value means worse, then nullsLast is appropriate.
        // For dates, typically newer is better, so desc.
        (
          p.citationNormalizedPercentile.desc.nullsLast, // Higher percentile first, NULLs last
          p.citedByCount.desc.nullsLast,                 // Higher count first, NULLs last
          p.publicationDate.desc.nullsLast               // Newer publication date first, NULLs last
        )
      }
      .drop(offset)
      .take(pageSize)

    db.run(sortedAndPaginatedQuery.result).flatMap { paginatedPublications =>
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

  override def getAllDois: Future[Seq[String]] = {
    db.run(publications.filter(_.doi.isDefined).map(_.doi.get).result)
  }
  
  override def savePublication(updatedPublication: Publication): Future[Publication] = {
    val query = publications.filter { p =>
      // Combine conditions with OR. If updatedPublication.openAlexId is None,
      // p.openAlexId === updatedPublication.openAlexId will resolve to false.
      // This is the idiomatic way to compare Option columns in Slick.
      (p.openAlexId === updatedPublication.openAlexId) ||
        (p.doi === updatedPublication.doi)
    }

    db.run(query.result.headOption).flatMap {
      case Some(existingPublication) =>
        // Publication exists, update it
        // Ensure the ID of the publication passed to update is the existing one
        val publicationToUpdate = updatedPublication.copy(id = existingPublication.id)
        db.run(publications.filter(_.id === existingPublication.id).update(publicationToUpdate))
          .map(_ => publicationToUpdate) // Return the updated publication
      case None =>
        // Publication does not exist, insert a new one
        db.run((publications returning publications.map(_.id) into ((pub, id) => pub.copy(id = Some(id)))) += updatedPublication)
    }
  }

  override def findByDoi(doi: String): Future[Option[Publication]] = db.run(publications.filter(_.doi === doi).result.headOption)
}