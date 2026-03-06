package services

import jakarta.inject.{Inject, Singleton}
import models.domain.billing.{PatronSubscription, PatronSummary, PatronTier}
import play.api.Logging
import repositories.PatronSubscriptionRepository

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PatronageService @Inject()(
                                  subscriptionRepo: PatronSubscriptionRepository
                                )(implicit ec: ExecutionContext) extends Logging {

  def createSubscription(
                          userId: UUID,
                          tier: String,
                          billingInterval: String,
                          paymentProvider: String,
                          providerSubscriptionId: Option[String] = None,
                          providerCustomerId: Option[String] = None
                        ): Future[Either[String, PatronSubscription]] = {
    if (!PatronSubscription.ValidTiers.contains(tier))
      return Future.successful(Left(s"Invalid patron tier: $tier"))
    if (!PatronSubscription.ValidIntervals.contains(billingInterval))
      return Future.successful(Left(s"Invalid billing interval: $billingInterval"))
    if (!PatronSubscription.ValidProviders.contains(paymentProvider))
      return Future.successful(Left(s"Invalid payment provider: $paymentProvider"))

    subscriptionRepo.findActiveByUserId(userId).flatMap {
      case Some(existing) =>
        Future.successful(Left(s"User already has an active subscription (tier: ${existing.patronTier})"))
      case None =>
        val amountCents = PatronTier.amountCents(tier, billingInterval)
        val now = LocalDateTime.now()
        val periodEnd = billingInterval match {
          case "MONTHLY" => now.plusMonths(1)
          case "YEARLY" => now.plusYears(1)
        }

        val subscription = PatronSubscription(
          userId = userId,
          patronTier = tier,
          paymentProvider = paymentProvider,
          providerSubscriptionId = providerSubscriptionId,
          providerCustomerId = providerCustomerId,
          amountCents = amountCents,
          billingInterval = billingInterval,
          currentPeriodStart = Some(now),
          currentPeriodEnd = Some(periodEnd)
        )
        subscriptionRepo.create(subscription).map(Right(_))
    }
  }

  def cancelSubscription(subscriptionId: Int, userId: UUID): Future[Either[String, Boolean]] = {
    subscriptionRepo.findById(subscriptionId).flatMap {
      case None =>
        Future.successful(Left("Subscription not found"))
      case Some(sub) if sub.userId != userId =>
        Future.successful(Left("Not authorized to cancel this subscription"))
      case Some(sub) if sub.status != "ACTIVE" =>
        Future.successful(Left(s"Cannot cancel subscription with status: ${sub.status}"))
      case Some(_) =>
        subscriptionRepo.cancel(subscriptionId).map(Right(_))
    }
  }

  def getActiveSubscription(userId: UUID): Future[Option[PatronSubscription]] =
    subscriptionRepo.findActiveByUserId(userId)

  def getUserSubscriptions(userId: UUID): Future[Seq[PatronSubscription]] =
    subscriptionRepo.findByUserId(userId)

  def handlePaymentWebhook(event: WebhookEvent, provider: String): Future[Either[String, Boolean]] = {
    subscriptionRepo.findByProviderSubscriptionId(provider, event.providerSubscriptionId).flatMap {
      case None =>
        logger.warn(s"Webhook for unknown subscription: ${event.providerSubscriptionId}")
        Future.successful(Left("Subscription not found"))
      case Some(sub) =>
        event.eventType match {
          case "subscription.renewed" | "invoice.paid" =>
            val start = event.periodStart.getOrElse(LocalDateTime.now())
            val end = event.periodEnd.getOrElse(
              if (sub.billingInterval == "MONTHLY") start.plusMonths(1) else start.plusYears(1)
            )
            for {
              _ <- subscriptionRepo.updateStatus(sub.id.get, "ACTIVE")
              _ <- subscriptionRepo.updatePeriod(sub.id.get, start, end)
            } yield Right(true)

          case "subscription.cancelled" | "subscription.deleted" =>
            subscriptionRepo.cancel(sub.id.get).map(Right(_))

          case "invoice.payment_failed" =>
            subscriptionRepo.updateStatus(sub.id.get, "PAST_DUE").map(Right(_))

          case other =>
            logger.debug(s"Unhandled webhook event type: $other")
            Future.successful(Right(true))
        }
    }
  }

  def expireOverdueSubscriptions(): Future[Int] = {
    subscriptionRepo.findByStatus("ACTIVE").flatMap { active =>
      val now = LocalDateTime.now()
      val expired = active.filter { sub =>
        sub.currentPeriodEnd.exists(_.isBefore(now))
      }

      Future.sequence(expired.map { sub =>
        subscriptionRepo.updateStatus(sub.id.get, "EXPIRED")
      }).map(_.count(_ == true))
    }
  }

  def getPatronSummary: Future[PatronSummary] = {
    for {
      activeCount <- subscriptionRepo.countActive()
      tierCounts <- subscriptionRepo.countByTier()
    } yield {
      val monthlyRevenue = tierCounts.map { case (tier, count) =>
        val monthlyAmount = PatronTier.amountCents(tier, "MONTHLY")
        monthlyAmount * count
      }.sum

      PatronSummary(
        activePatrons = activeCount,
        tierCounts = tierCounts,
        monthlyRevenueCents = monthlyRevenue
      )
    }
  }

  def isPatron(userId: UUID): Future[Boolean] =
    subscriptionRepo.findActiveByUserId(userId).map(_.isDefined)

  def getPatronTier(userId: UUID): Future[Option[String]] =
    subscriptionRepo.findActiveByUserId(userId).map(_.map(_.patronTier))
}
