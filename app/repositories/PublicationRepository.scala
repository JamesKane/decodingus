package repositories

import jakarta.inject.Inject
import models.api.PublicationWithEnaStudiesAndSampleCount
import models.dal.DatabaseSchema
import models.{EnaStudy, Publication}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait PublicationRepository {
  def getAllPublications(): Future[Seq[Publication]]

  def getEnaStudiesForPublication(publicationId: Int): Future[Seq[EnaStudy]]

  def findPublicationsWithDetailsPaginated(page: Int, pageSize: Int): Future[Seq[PublicationWithEnaStudiesAndSampleCount]]

  def countAllPublications(): Future[Long]
}

class PublicationRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationRepository with HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  private val publications = DatabaseSchema.publications
  private val publicationEnaStudies = DatabaseSchema.publicationEnaStudies
  private val enaStudies = DatabaseSchema.enaStudies
  private val publicationBiosamples = DatabaseSchema.publicationBiosamples

  override def getAllPublications(): Future[Seq[Publication]] = db.run(publications.result)

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