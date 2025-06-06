package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.publications.PublicationBiosample
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

/**
 * The `PublicationBiosampleRepository` trait provides an abstraction for operations
 * related to biosample data associated with a specific publication.
 */
trait PublicationBiosampleRepository {
  /**
   * Counts the number of biosamples associated with the specified publication.
   *
   * @param publicationId The unique identifier of the publication.
   * @return A `Future` containing the count of associated biosamples as an `Int`.
   */
  def countSamplesForPublication(publicationId: Int): Future[Int]

  /**
   * Creates a new association between a publication and a biosample in the system.
   *
   * @param link The `PublicationBiosample` object representing the association to be created. 
   *             It contains the publication ID and the biosample ID to be linked.
   * @return A `Future` containing the created `PublicationBiosample` object with the details of the new association.
   */
  def create(link: PublicationBiosample): Future[PublicationBiosample]

  /**
   * Retrieves all `PublicationBiosample` entries associated with the specified biosample ID.
   *
   * @param biosampleId The unique identifier of the biosample for which associated entries are to be retrieved.
   * @return A `Future` containing a sequence of `PublicationBiosample` objects associated with the given biosample ID.
   */
  def findByBiosampleId(biosampleId: Int): Future[Seq[PublicationBiosample]]
}

class PublicationBiosampleRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationBiosampleRepository with HasDatabaseConfigProvider[JdbcProfile] with Logging {

  import profile.api.*

  private val publicationBiosamples = DatabaseSchema.domain.publications.publicationBiosamples

  override def countSamplesForPublication(publicationId: Int): Future[Int] = {
    val query = publicationBiosamples.filter(_.publicationId === publicationId).length
    db.run(query.result)
  }

  override def create(link: PublicationBiosample): Future[PublicationBiosample] = {
    logger.info(s"Linking: $link")

    // Create a query to check for existing link
    val existingQuery = publicationBiosamples
      .filter(pb =>
        pb.publicationId === link.publicationId &&
          pb.biosampleId === link.biosampleId
      )

    // Create an upsert action
    val upsertAction = existingQuery.result.headOption.flatMap {
      case Some(_) =>
        // Link already exists, no need to update
        DBIO.successful(link)
      case None =>
        // Insert new link
        publicationBiosamples += link
    }.transactionally

    db.run(upsertAction).map(_ => link)
  }

  override def findByBiosampleId(biosampleId: Int): Future[Seq[PublicationBiosample]] = {
    db.run(publicationBiosamples.filter(_.biosampleId === biosampleId).result)
  }
}
