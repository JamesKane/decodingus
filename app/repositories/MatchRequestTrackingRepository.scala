package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.ibd.MatchRequestTracking
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait MatchRequestTrackingRepository {
  def create(request: MatchRequestTracking): Future[MatchRequestTracking]
  def findByAtUri(atUri: String): Future[Option[MatchRequestTracking]]
  def findPendingForSample(sampleGuid: UUID): Future[Seq[MatchRequestTracking]]
  def findSentByDid(did: String): Future[Seq[MatchRequestTracking]]
  def updateStatus(atUri: String, status: String): Future[Boolean]
  def upsertFromFirehose(request: MatchRequestTracking): Future[MatchRequestTracking]
}

@Singleton
class MatchRequestTrackingRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with MatchRequestTrackingRepository
    with Logging {

  import profile.api.*

  private val requests = DatabaseSchema.domain.ibd.matchRequestTracking

  override def create(request: MatchRequestTracking): Future[MatchRequestTracking] =
    db.run((requests returning requests.map(_.id) into ((r, id) => r.copy(id = Some(id)))) += request)

  override def findByAtUri(atUri: String): Future[Option[MatchRequestTracking]] =
    db.run(requests.filter(_.atUri === atUri).result.headOption)

  override def findPendingForSample(sampleGuid: UUID): Future[Seq[MatchRequestTracking]] =
    db.run(
      requests.filter(r => r.toSampleGuid === sampleGuid && r.status === "PENDING")
        .sortBy(_.createdAt.desc).result
    )

  override def findSentByDid(did: String): Future[Seq[MatchRequestTracking]] =
    db.run(requests.filter(_.requesterDid === did).sortBy(_.createdAt.desc).result)

  override def updateStatus(atUri: String, status: String): Future[Boolean] =
    db.run(
      requests.filter(_.atUri === atUri)
        .map(r => (r.status, r.updatedAt))
        .update((status, ZonedDateTime.now()))
    ).map(_ > 0)

  override def upsertFromFirehose(request: MatchRequestTracking): Future[MatchRequestTracking] = {
    findByAtUri(request.atUri).flatMap {
      case Some(existing) =>
        db.run(
          requests.filter(_.atUri === request.atUri)
            .map(r => (r.status, r.message, r.updatedAt, r.expiresAt))
            .update((request.status, request.message, ZonedDateTime.now(), request.expiresAt))
        ).map(_ => request.copy(id = existing.id))
      case None =>
        create(request)
    }
  }
}
