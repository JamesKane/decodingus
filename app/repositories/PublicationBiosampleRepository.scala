package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
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
}

class PublicationBiosampleRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationBiosampleRepository with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api.*

  private val publicationBiosamples = DatabaseSchema.domain.genomics.publicationBiosamples

  override def countSamplesForPublication(publicationId: Int): Future[Int] = {
    val query = publicationBiosamples.filter(_.publicationId === publicationId).length
    db.run(query.result)
  }
}
