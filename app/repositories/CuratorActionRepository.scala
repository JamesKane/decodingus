package repositories

import jakarta.inject.Inject
import models.domain.discovery.*
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait CuratorActionRepository {
  def create(action: CuratorAction): Future[CuratorAction]
  def findByTarget(targetType: CuratorTargetType, targetId: Int): Future[Seq[CuratorAction]]
  def findByCurator(curatorId: String, limit: Int = 50, offset: Int = 0): Future[Seq[CuratorAction]]
  def findRecent(limit: Int = 50, offset: Int = 0): Future[Seq[CuratorAction]]
}

class CuratorActionRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with CuratorActionRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.curatorActions
  import models.dal.MyPostgresProfile.api.*

  override def create(action: CuratorAction): Future[CuratorAction] = {
    val dbAction = (curatorActions returning curatorActions.map(_.id)
      into ((row, id) => row.copy(id = Some(id)))) += action
    runQuery(dbAction)
  }

  override def findByTarget(targetType: CuratorTargetType, targetId: Int): Future[Seq[CuratorAction]] =
    runQuery(curatorActions
      .filter(a => a.targetType === targetType && a.targetId === targetId)
      .sortBy(_.createdAt.desc)
      .result)

  override def findByCurator(curatorId: String, limit: Int, offset: Int): Future[Seq[CuratorAction]] =
    runQuery(curatorActions
      .filter(_.curatorId === curatorId)
      .sortBy(_.createdAt.desc)
      .drop(offset)
      .take(limit)
      .result)

  override def findRecent(limit: Int, offset: Int): Future[Seq[CuratorAction]] =
    runQuery(curatorActions
      .sortBy(_.createdAt.desc)
      .drop(offset)
      .take(limit)
      .result)
}
