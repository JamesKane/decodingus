package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.billing.PatronSubscription
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait PatronSubscriptionRepository {
  def create(subscription: PatronSubscription): Future[PatronSubscription]
  def findById(id: Int): Future[Option[PatronSubscription]]
  def findByUserId(userId: UUID): Future[Seq[PatronSubscription]]
  def findActiveByUserId(userId: UUID): Future[Option[PatronSubscription]]
  def findByProviderSubscriptionId(provider: String, providerSubId: String): Future[Option[PatronSubscription]]
  def findByStatus(status: String): Future[Seq[PatronSubscription]]
  def updateStatus(id: Int, status: String): Future[Boolean]
  def updatePeriod(id: Int, periodStart: LocalDateTime, periodEnd: LocalDateTime): Future[Boolean]
  def cancel(id: Int): Future[Boolean]
  def countByTier(): Future[Map[String, Int]]
  def countActive(): Future[Int]
}

class PatronSubscriptionRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PatronSubscriptionRepository
    with Logging {

  import models.dal.MyPostgresProfile.api.*

  private val subscriptions = DatabaseSchema.billing.patronSubscriptions

  override def create(subscription: PatronSubscription): Future[PatronSubscription] =
    runQuery(
      (subscriptions returning subscriptions.map(_.id) into ((s, id) => s.copy(id = Some(id)))) += subscription
    )

  override def findById(id: Int): Future[Option[PatronSubscription]] =
    runQuery(subscriptions.filter(_.id === id).result.headOption)

  override def findByUserId(userId: UUID): Future[Seq[PatronSubscription]] =
    runQuery(subscriptions.filter(_.userId === userId).sortBy(_.createdAt.desc).result)

  override def findActiveByUserId(userId: UUID): Future[Option[PatronSubscription]] =
    runQuery(
      subscriptions.filter(s => s.userId === userId && s.status === "ACTIVE")
        .sortBy(_.createdAt.desc).result.headOption
    )

  override def findByProviderSubscriptionId(provider: String, providerSubId: String): Future[Option[PatronSubscription]] =
    runQuery(
      subscriptions.filter(s =>
        s.paymentProvider === provider && s.providerSubscriptionId === providerSubId
      ).result.headOption
    )

  override def findByStatus(status: String): Future[Seq[PatronSubscription]] =
    runQuery(subscriptions.filter(_.status === status).sortBy(_.createdAt.desc).result)

  override def updateStatus(id: Int, status: String): Future[Boolean] =
    runQuery(
      subscriptions.filter(_.id === id)
        .map(s => (s.status, s.updatedAt))
        .update((status, LocalDateTime.now()))
    ).map(_ > 0)

  override def updatePeriod(id: Int, periodStart: LocalDateTime, periodEnd: LocalDateTime): Future[Boolean] =
    runQuery(
      subscriptions.filter(_.id === id)
        .map(s => (s.currentPeriodStart, s.currentPeriodEnd, s.updatedAt))
        .update((Some(periodStart), Some(periodEnd), LocalDateTime.now()))
    ).map(_ > 0)

  override def cancel(id: Int): Future[Boolean] =
    runQuery(
      subscriptions.filter(_.id === id)
        .map(s => (s.status, s.cancelledAt, s.updatedAt))
        .update(("CANCELLED", Some(LocalDateTime.now()), LocalDateTime.now()))
    ).map(_ > 0)

  override def countByTier(): Future[Map[String, Int]] =
    runQuery(
      subscriptions.filter(_.status === "ACTIVE")
        .groupBy(_.patronTier).map { case (tier, group) => (tier, group.length) }
        .result
    ).map(_.toMap)

  override def countActive(): Future[Int] =
    runQuery(subscriptions.filter(_.status === "ACTIVE").length.result)
}
