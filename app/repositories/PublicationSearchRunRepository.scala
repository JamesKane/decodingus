package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.publications.PublicationSearchRun
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait PublicationSearchRunRepository {
  def create(run: PublicationSearchRun): Future[PublicationSearchRun]
  def listByConfig(configId: Int, limit: Int): Future[Seq[PublicationSearchRun]]
}

@Singleton
class PublicationSearchRunRepositoryImpl @Inject()(
  override protected val dbConfigProvider: DatabaseConfigProvider
)(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PublicationSearchRunRepository {

  import models.dal.MyPostgresProfile.api.*

  private val runsTable = DatabaseSchema.domain.publications.publicationSearchRuns

  override def create(run: PublicationSearchRun): Future[PublicationSearchRun] = {
    db.run((runsTable returning runsTable.map(_.id)
      into ((r, id) => r.copy(id = Some(id)))) += run)
  }

  override def listByConfig(configId: Int, limit: Int): Future[Seq[PublicationSearchRun]] = {
    db.run(runsTable.filter(_.configId === configId).sortBy(_.runAt.desc).take(limit).result)
  }
}
