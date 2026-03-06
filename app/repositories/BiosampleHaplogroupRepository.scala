package repositories

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.genomics.BiosampleHaplogroup
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait BiosampleHaplogroupRepository {
  def findBySampleGuid(sampleGuid: UUID): Future[Option[BiosampleHaplogroup]]
  def upsert(bh: BiosampleHaplogroup): Future[Int]
  def updateYHaplogroup(sampleGuid: UUID, haplogroupId: Int): Future[Boolean]
  def updateMtHaplogroup(sampleGuid: UUID, haplogroupId: Int): Future[Boolean]
  def findByHaplogroupId(haplogroupId: Int, haplogroupType: HaplogroupType): Future[Seq[BiosampleHaplogroup]]
}

class BiosampleHaplogroupRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with BiosampleHaplogroupRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.genomics.biosampleHaplogroups
  import models.dal.MyPostgresProfile.api.*

  override def findBySampleGuid(sampleGuid: UUID): Future[Option[BiosampleHaplogroup]] =
    runQuery(biosampleHaplogroups.filter(_.sampleGuid === sampleGuid).result.headOption)

  override def upsert(bh: BiosampleHaplogroup): Future[Int] =
    runQuery(biosampleHaplogroups.insertOrUpdate(bh))

  override def updateYHaplogroup(sampleGuid: UUID, haplogroupId: Int): Future[Boolean] =
    runQuery(biosampleHaplogroups
      .filter(_.sampleGuid === sampleGuid)
      .map(_.yHaplogroupId)
      .update(Some(haplogroupId))
      .map(_ > 0))

  override def updateMtHaplogroup(sampleGuid: UUID, haplogroupId: Int): Future[Boolean] =
    runQuery(biosampleHaplogroups
      .filter(_.sampleGuid === sampleGuid)
      .map(_.mtHaplogroupId)
      .update(Some(haplogroupId))
      .map(_ > 0))

  override def findByHaplogroupId(haplogroupId: Int, haplogroupType: HaplogroupType): Future[Seq[BiosampleHaplogroup]] = {
    haplogroupType match {
      case HaplogroupType.Y =>
        runQuery(biosampleHaplogroups.filter(_.yHaplogroupId === haplogroupId).result)
      case HaplogroupType.MT =>
        runQuery(biosampleHaplogroups.filter(_.mtHaplogroupId === haplogroupId).result)
    }
  }
}
