package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.genomics.SequencingLab
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait SequencingLabRepository {
  def list(): Future[Seq[SequencingLab]]
  def findById(id: Int): Future[Option[SequencingLab]]
  def create(lab: SequencingLab): Future[SequencingLab]
  def update(id: Int, update: SequencingLab): Future[Option[SequencingLab]]
  def delete(id: Int): Future[Boolean]
}

@Singleton
class SequencingLabRepositoryImpl @Inject()(
  override protected val dbConfigProvider: DatabaseConfigProvider
)(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider) with SequencingLabRepository {

  import models.dal.MyPostgresProfile.api.*

  private val labs = DatabaseSchema.domain.genomics.sequencingLabs

  override def list(): Future[Seq[SequencingLab]] = db.run(labs.sortBy(_.id.asc.nullsLast).result)

  override def findById(id: Int): Future[Option[SequencingLab]] = db.run(labs.filter(_.id === id).result.headOption)

  override def create(lab: SequencingLab): Future[SequencingLab] = {
    val insert = (labs returning labs.map(_.id) into { case (row, id) => row.copy(id = Some(id)) }) += lab.copy(id = None)
    db.run(insert)
  }

  override def update(id: Int, update: SequencingLab): Future[Option[SequencingLab]] = {
    val q = labs.filter(_.id === id)
    val action = for {
      existing <- q.result.headOption
      updatedOpt <- existing match {
        case Some(_) =>
          q.map(l => (l.name, l.isD2c, l.websiteUrl, l.descriptionMarkdown, l.updatedAt))
            .update((update.name, update.isD2c, update.websiteUrl, update.descriptionMarkdown, update.updatedAt))
            .map(_ => Some(update.copy(id = Some(id))))
        case None => DBIO.successful(None)
      }
    } yield updatedOpt

    db.run(action.transactionally)
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(labs.filter(_.id === id).delete.map(_ > 0))
  }
}
