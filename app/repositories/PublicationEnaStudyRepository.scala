package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.publications.PublicationEnaStudy
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}


trait PublicationEnaStudyRepository {
  def create(link: PublicationEnaStudy): Future[PublicationEnaStudy]

  def findByPublicationId(publicationId: Int): Future[Seq[PublicationEnaStudy]]

  def findByEnaStudyId(enaStudyId: Int): Future[Seq[PublicationEnaStudy]]

  def update(link: PublicationEnaStudy): Future[Option[PublicationEnaStudy]]

  def delete(publicationId: Int, enaStudyId: Int): Future[Int]

  def exists(publicationId: Int, enaStudyId: Int): Future[Boolean]

  def findAll(): Future[Seq[PublicationEnaStudy]]
}

@Singleton
class PublicationEnaStudyRepositoryImpl @Inject()(
                                                   dbConfigProvider: DatabaseConfigProvider
                                                 )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PublicationEnaStudyRepository {

  import models.dal.MyPostgresProfile.api.*

  val publicationEnaStudies = DatabaseSchema.domain.publications.publicationEnaStudies

  override def create(link: PublicationEnaStudy): Future[PublicationEnaStudy] = {
    runQuery(
      publicationEnaStudies
        .returning(publicationEnaStudies)
        .insertOrUpdate(link)
    ).map(_ => link)
  }

  override def findAll(): Future[Seq[PublicationEnaStudy]] = {
    db.run(publicationEnaStudies.result)
  }

  override def findByPublicationId(publicationId: Int): Future[Seq[PublicationEnaStudy]] = {
    db.run(
      publicationEnaStudies
        .filter(_.publicationId === publicationId)
        .result
    )
  }

  override def findByEnaStudyId(enaStudyId: Int): Future[Seq[PublicationEnaStudy]] = {
    db.run(
      publicationEnaStudies
        .filter(_.enaStudyId === enaStudyId)
        .result
    )
  }

  override def update(link: PublicationEnaStudy): Future[Option[PublicationEnaStudy]] = {
    db.run(
      publicationEnaStudies
        .filter(r =>
          r.publicationId === link.publicationId &&
            r.enaStudyId === link.studyId
        )
        .update(link)
    ).map {
      case 0 => None // No rows were updated
      case _ => Some(link)
    }
  }

  override def delete(publicationId: Int, enaStudyId: Int): Future[Int] = {
    db.run(
      publicationEnaStudies
        .filter(r =>
          r.publicationId === publicationId &&
            r.enaStudyId === enaStudyId
        )
        .delete
    )
  }

  override def exists(publicationId: Int, enaStudyId: Int): Future[Boolean] = {
    db.run(
      publicationEnaStudies
        .filter(r =>
          r.publicationId === publicationId &&
            r.enaStudyId === enaStudyId
        )
        .exists
        .result
    )
  }
}


