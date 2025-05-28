package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.publications.PublicationGenomicStudy
import play.api.db.slick.DatabaseConfigProvider

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}


trait PublicationGenomicStudyRepository {
  def create(link: PublicationGenomicStudy): Future[PublicationGenomicStudy]

  def findByPublicationId(publicationId: Int): Future[Seq[PublicationGenomicStudy]]

  def findByEnaStudyId(enaStudyId: Int): Future[Seq[PublicationGenomicStudy]]

  def update(link: PublicationGenomicStudy): Future[Option[PublicationGenomicStudy]]

  def delete(publicationId: Int, enaStudyId: Int): Future[Int]

  def exists(publicationId: Int, enaStudyId: Int): Future[Boolean]

  def findAll(): Future[Seq[PublicationGenomicStudy]]
}

@Singleton
class PublicationGenomicStudyRepositoryImpl @Inject()(
                                                   dbConfigProvider: DatabaseConfigProvider
                                                 )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PublicationGenomicStudyRepository {

  import models.dal.MyPostgresProfile.api.*

  val publicationGenomicStudies = DatabaseSchema.domain.publications.publicationGenomicStudies

  override def create(link: PublicationGenomicStudy): Future[PublicationGenomicStudy] = {
    runQuery(
      publicationGenomicStudies
        .returning(publicationGenomicStudies)
        .insertOrUpdate(link)
    ).map(_ => link)
  }

  override def findAll(): Future[Seq[PublicationGenomicStudy]] = {
    db.run(publicationGenomicStudies.result)
  }

  override def findByPublicationId(publicationId: Int): Future[Seq[PublicationGenomicStudy]] = {
    db.run(
      publicationGenomicStudies
        .filter(_.publicationId === publicationId)
        .result
    )
  }

  override def findByEnaStudyId(enaStudyId: Int): Future[Seq[PublicationGenomicStudy]] = {
    db.run(
      publicationGenomicStudies
        .filter(_.genomicStudyId === enaStudyId)
        .result
    )
  }

  override def update(link: PublicationGenomicStudy): Future[Option[PublicationGenomicStudy]] = {
    db.run(
      publicationGenomicStudies
        .filter(r =>
          r.publicationId === link.publicationId &&
            r.genomicStudyId === link.studyId
        )
        .update(link)
    ).map {
      case 0 => None // No rows were updated
      case _ => Some(link)
    }
  }

  override def delete(publicationId: Int, enaStudyId: Int): Future[Int] = {
    db.run(
      publicationGenomicStudies
        .filter(r =>
          r.publicationId === publicationId &&
            r.genomicStudyId === enaStudyId
        )
        .delete
    )
  }

  override def exists(publicationId: Int, enaStudyId: Int): Future[Boolean] = {
    db.run(
      publicationGenomicStudies
        .filter(r =>
          r.publicationId === publicationId &&
            r.genomicStudyId === enaStudyId
        )
        .exists
        .result
    )
  }
}


