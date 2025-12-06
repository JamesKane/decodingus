package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.publications.PublicationCitizenBiosample
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait PublicationCitizenBiosampleRepository {
  def create(link: PublicationCitizenBiosample): Future[PublicationCitizenBiosample]
  def deleteByCitizenBiosampleId(citizenBiosampleId: Int): Future[Int]
}

class PublicationCitizenBiosampleRepositoryImpl @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends PublicationCitizenBiosampleRepository with HasDatabaseConfigProvider[JdbcProfile] with Logging {

  import profile.api.*

  private val publicationCitizenBiosamples = DatabaseSchema.domain.publications.publicationCitizenBiosamples

  override def create(link: PublicationCitizenBiosample): Future[PublicationCitizenBiosample] = {
    val existingQuery = publicationCitizenBiosamples
      .filter(pb =>
        pb.publicationId === link.publicationId &&
          pb.citizenBiosampleId === link.citizenBiosampleId
      )

    val upsertAction = existingQuery.result.headOption.flatMap {
      case Some(_) => DBIO.successful(link)
      case None => publicationCitizenBiosamples += link
    }.transactionally

    db.run(upsertAction).map(_ => link)
  }

  override def deleteByCitizenBiosampleId(citizenBiosampleId: Int): Future[Int] = {
    db.run(publicationCitizenBiosamples.filter(_.citizenBiosampleId === citizenBiosampleId).delete)
  }
}
