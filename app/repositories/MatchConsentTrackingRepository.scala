package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.ibd.MatchConsentTracking
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait MatchConsentTrackingRepository {
  def create(consent: MatchConsentTracking): Future[MatchConsentTracking]
  def findByAtUri(atUri: String): Future[Option[MatchConsentTracking]]
  def findBySampleGuid(sampleGuid: UUID): Future[Option[MatchConsentTracking]]
  def findByDid(did: String): Future[Seq[MatchConsentTracking]]
  def findActiveConsentForSample(sampleGuid: UUID): Future[Option[MatchConsentTracking]]
  def revoke(atUri: String): Future[Boolean]
  def upsertFromFirehose(consent: MatchConsentTracking): Future[MatchConsentTracking]
  def deleteByAtUri(atUri: String): Future[Boolean]
}

@Singleton
class MatchConsentTrackingRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with MatchConsentTrackingRepository
    with Logging {

  import profile.api.*
  import models.dal.MyPostgresProfile.api.playJsonTypeMapper

  private val consents = DatabaseSchema.domain.ibd.matchConsentTracking

  override def create(consent: MatchConsentTracking): Future[MatchConsentTracking] =
    db.run((consents returning consents.map(_.id) into ((c, id) => c.copy(id = Some(id)))) += consent)

  override def findByAtUri(atUri: String): Future[Option[MatchConsentTracking]] =
    db.run(consents.filter(_.atUri === atUri).result.headOption)

  override def findBySampleGuid(sampleGuid: UUID): Future[Option[MatchConsentTracking]] =
    db.run(consents.filter(_.sampleGuid === sampleGuid).result.headOption)

  override def findByDid(did: String): Future[Seq[MatchConsentTracking]] =
    db.run(consents.filter(_.consentingDid === did).result)

  override def findActiveConsentForSample(sampleGuid: UUID): Future[Option[MatchConsentTracking]] =
    db.run(
      consents.filter(c => c.sampleGuid === sampleGuid && c.revokedAt.isEmpty)
        .result.headOption
    )

  override def revoke(atUri: String): Future[Boolean] =
    db.run(
      consents.filter(c => c.atUri === atUri && c.revokedAt.isEmpty)
        .map(_.revokedAt)
        .update(Some(ZonedDateTime.now()))
    ).map(_ > 0)

  override def upsertFromFirehose(consent: MatchConsentTracking): Future[MatchConsentTracking] = {
    findByAtUri(consent.atUri).flatMap {
      case Some(existing) =>
        db.run(
          consents.filter(_.atUri === consent.atUri)
            .map(c => (c.consentLevel, c.allowedMatchTypes, c.shareContactInfo, c.expiresAt))
            .update((consent.consentLevel, consent.allowedMatchTypes, consent.shareContactInfo, consent.expiresAt))
        ).map(_ => consent.copy(id = existing.id))
      case None =>
        create(consent)
    }
  }

  override def deleteByAtUri(atUri: String): Future[Boolean] =
    db.run(consents.filter(_.atUri === atUri).delete).map(_ > 0)
}
