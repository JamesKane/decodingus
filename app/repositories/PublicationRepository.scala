package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.{EnaStudy, Publication}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait PublicationRepository {
  def getAllPublications(): Future[Seq[Publication]]
  def getEnaStudiesForPublication(publicationId: Int): Future[Seq[EnaStudy]]
}

class PublicationRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationRepository with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._
  private val publications = DatabaseSchema.publications
  private val publicationEnaStudies = DatabaseSchema.publicationEnaStudies
  private val enaStudies = DatabaseSchema.enaStudies

  override def getAllPublications(): Future[Seq[Publication]] = db.run(publications.result)

  override def getEnaStudiesForPublication(publicationId: Int): Future[Seq[EnaStudy]] = {
    val query = for {
      pes <- publicationEnaStudies if pes.publicationId === publicationId
      es <- enaStudies if es.id === pes.enaStudyId
    } yield es
    db.run(query.result)
  }
}