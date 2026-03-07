package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.ibd.PopulationBreakdownCache
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait PopulationBreakdownCacheRepository {
  def upsert(cache: PopulationBreakdownCache): Future[PopulationBreakdownCache]
  def findBySampleGuid(sampleGuid: UUID): Future[Option[PopulationBreakdownCache]]
  def findAll(): Future[Seq[PopulationBreakdownCache]]
  def findAllSampleGuids(): Future[Seq[UUID]]
  def deleteBySampleGuid(sampleGuid: UUID): Future[Boolean]
}

@Singleton
class PopulationBreakdownCacheRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with PopulationBreakdownCacheRepository
    with Logging {

  import profile.api.*
  import models.dal.MyPostgresProfile.api.playJsonTypeMapper

  private val cache = DatabaseSchema.domain.ibd.populationBreakdownCache

  override def upsert(entry: PopulationBreakdownCache): Future[PopulationBreakdownCache] = {
    findBySampleGuid(entry.sampleGuid).flatMap {
      case Some(existing) if existing.breakdownHash == entry.breakdownHash =>
        Future.successful(existing)
      case Some(existing) =>
        db.run(
          cache.filter(_.sampleGuid === entry.sampleGuid)
            .map(c => (c.breakdown, c.breakdownHash, c.cachedAt, c.sourceAtUri))
            .update((entry.breakdown, entry.breakdownHash, ZonedDateTime.now(), entry.sourceAtUri))
        ).map(_ => entry.copy(id = existing.id))
      case None =>
        db.run((cache returning cache.map(_.id) into ((c, id) => c.copy(id = Some(id)))) += entry)
    }
  }

  override def findBySampleGuid(sampleGuid: UUID): Future[Option[PopulationBreakdownCache]] =
    db.run(cache.filter(_.sampleGuid === sampleGuid).result.headOption)

  override def findAll(): Future[Seq[PopulationBreakdownCache]] =
    db.run(cache.result)

  override def findAllSampleGuids(): Future[Seq[UUID]] =
    db.run(cache.map(_.sampleGuid).result)

  override def deleteBySampleGuid(sampleGuid: UUID): Future[Boolean] =
    db.run(cache.filter(_.sampleGuid === sampleGuid).delete).map(_ > 0)
}
