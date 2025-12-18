package repositories

import jakarta.inject.Inject
import models.api.PublicationWithEnaStudiesAndSampleCount
import models.dal.DatabaseSchema
import models.domain.publications.{GenomicStudy, Publication}
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
  def getEnaStudiesForPublication(publicationId: Int): Future[Seq[GenomicStudy]]

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

  /**
   * Finds a publication in the repository by its DOI.
   *
   * @param doi the DOI (Digital Object Identifier) of the publication to search for
   * @return a Future containing an Option of Publication, where the Option is:
   *         - Some(Publication) if a publication with the specified DOI exists
   *         - None if no publication with the specified DOI is found
   */
  def findByDoi(doi: String): Future[Option[Publication]]

  /**
   * Saves a publication to the database. If a publication with the same OpenAlex ID or DOI
   * already exists, it updates the existing record; otherwise, it inserts a new one.
   *
   * @param publication The publication to save or update.
   * @return A Future containing the saved or updated Publication object (with its database ID).
   */
  def savePublication(publication: Publication): Future[Publication]

  /**
   * Searches publications by a query string, matching against title, authors, and abstract.
   * Results are paginated and sorted by relevance (citation percentile, cited by count, date).
   *
   * @param query    The search query string to match against title, authors, and abstract
   * @param page     The page number to retrieve (1-based index)
   * @param pageSize The number of records to include in each page
   * @return A Future containing a tuple of (matching publications with details, total count)
   */
  def searchPublications(query: String, page: Int, pageSize: Int): Future[(Seq[PublicationWithEnaStudiesAndSampleCount], Long)]
}

class PublicationRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationRepository with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api.*

  private val publications = DatabaseSchema.domain.publications.publications
  private val publicationEnaStudies = DatabaseSchema.domain.publications.publicationGenomicStudies
  private val enaStudies = DatabaseSchema.domain.publications.genomicStudies
  private val publicationBiosamples = DatabaseSchema.domain.publications.publicationBiosamples

  override def getAllPublications: Future[Seq[Publication]] = db.run(publications.result)

  override def getEnaStudiesForPublication(publicationId: Int): Future[Seq[GenomicStudy]] = {
    val query = for {
      pes <- publicationEnaStudies if pes.publicationId === publicationId
      es <- enaStudies if es.id === pes.genomicStudyId
    } yield es
    db.run(query.result)
  }

  override def findPublicationsWithDetailsPaginated(page: Int, pageSize: Int): Future[Seq[PublicationWithEnaStudiesAndSampleCount]] = {
    val offset = (page - 1) * pageSize

    // Apply sorting first, then pagination
    val sortedAndPaginatedQuery = publications
      .sortBy { p =>
        (
          p.citationNormalizedPercentile.desc.nullsLast,
          p.citedByCount.desc.nullsLast,
          p.publicationDate.desc.nullsLast
        )
      }
      .drop(offset)
      .take(pageSize)

    db.run(sortedAndPaginatedQuery.result).flatMap { paginatedPublications =>
      if (paginatedPublications.isEmpty) {
        Future.successful(Seq.empty)
      } else {
        assemblePublicationsWithDetails(paginatedPublications)
      }
    }
  }

  /**
   * Assembles publication details using batch queries instead of N+1 pattern.
   * Uses 2 additional queries regardless of the number of publications.
   */
  private def assemblePublicationsWithDetails(paginatedPublications: Seq[Publication]): Future[Seq[PublicationWithEnaStudiesAndSampleCount]] = {
    val publicationIds = paginatedPublications.flatMap(_.id)

    // Batch query 1: Get all genomic studies for all publication IDs
    val studiesQuery = (for {
      pes <- publicationEnaStudies if pes.publicationId.inSet(publicationIds)
      es <- enaStudies if es.id === pes.genomicStudyId
    } yield (pes.publicationId, es)).result

    // Batch query 2: Get all biosample counts for all publication IDs
    val countsQuery = publicationBiosamples
      .filter(_.publicationId.inSet(publicationIds))
      .groupBy(_.publicationId)
      .map { case (pubId, group) => (pubId, group.length) }
      .result

    for {
      studiesWithPubId <- db.run(studiesQuery)
      counts <- db.run(countsQuery)
    } yield {
      // Group studies by publication ID
      val studiesByPubId: Map[Int, Seq[GenomicStudy]] = studiesWithPubId
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap

      // Convert counts to map
      val countsByPubId: Map[Int, Int] = counts.toMap

      // Assemble results maintaining original order
      paginatedPublications.map { publication =>
        val pubId = publication.id.getOrElse(0)
        PublicationWithEnaStudiesAndSampleCount(
          publication,
          studiesByPubId.getOrElse(pubId, Seq.empty),
          countsByPubId.getOrElse(pubId, 0)
        )
      }
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

  override def searchPublications(query: String, page: Int, pageSize: Int): Future[(Seq[PublicationWithEnaStudiesAndSampleCount], Long)] = {
    val offset = (page - 1) * pageSize
    val searchPattern = s"%${query.toLowerCase}%"

    // Filter by title, authors, or abstract (case-insensitive)
    val baseQuery = publications.filter { p =>
      p.title.toLowerCase.like(searchPattern) ||
        p.authors.map(_.toLowerCase).like(searchPattern) ||
        p.abstractSummary.map(_.toLowerCase).like(searchPattern)
    }

    // Get total count for pagination
    val countQuery = baseQuery.length.result

    // Apply sorting and pagination
    val sortedAndPaginatedQuery = baseQuery
      .sortBy { p =>
        (
          p.citationNormalizedPercentile.desc.nullsLast,
          p.citedByCount.desc.nullsLast,
          p.publicationDate.desc.nullsLast
        )
      }
      .drop(offset)
      .take(pageSize)

    for {
      totalCount <- db.run(countQuery)
      paginatedPublications <- db.run(sortedAndPaginatedQuery.result)
      publicationsWithDetails <- if (paginatedPublications.isEmpty) {
        Future.successful(Seq.empty)
      } else {
        assemblePublicationsWithDetails(paginatedPublications)
      }
    } yield (publicationsWithDetails, totalCount.toLong)
  }
}