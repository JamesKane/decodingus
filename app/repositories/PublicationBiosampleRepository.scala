package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait PublicationBiosampleRepository {
  def countSamplesForPublication(publicationId: Int): Future[Int]
}

class PublicationBiosampleRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationBiosampleRepository with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._
  private val publicationBiosamples = DatabaseSchema.publicationBiosamples
  private val biosamples = DatabaseSchema.biosamples

  override def countSamplesForPublication(publicationId: Int): Future[Int] = {
    val query = publicationBiosamples.filter(_.publicationId === publicationId).length
    db.run(query.result)
  }
}
