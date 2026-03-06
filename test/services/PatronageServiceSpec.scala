package services

import helpers.ServiceSpec
import models.domain.billing.PatronSubscription
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.PatronSubscriptionRepository

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class PatronageServiceSpec extends ServiceSpec {

  val mockRepo: PatronSubscriptionRepository = mock[PatronSubscriptionRepository]
  val service = new PatronageService(mockRepo)

  val userId: UUID = UUID.randomUUID()
  val now: LocalDateTime = LocalDateTime.now()

  val testSubscription: PatronSubscription = PatronSubscription(
    id = Some(1),
    userId = userId,
    patronTier = "SUPPORTER",
    status = "ACTIVE",
    paymentProvider = "STRIPE",
    providerSubscriptionId = Some("sub_123"),
    providerCustomerId = Some("cus_456"),
    amountCents = 200,
    billingInterval = "MONTHLY",
    currentPeriodStart = Some(now),
    currentPeriodEnd = Some(now.plusMonths(1))
  )

  override def beforeEach(): Unit = {
    reset(mockRepo)
  }

  "PatronageService.createSubscription" should {

    "create a monthly supporter subscription" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(None))
      when(mockRepo.create(any[PatronSubscription]))
        .thenAnswer(inv => Future.successful(inv.getArgument[PatronSubscription](0).copy(id = Some(1))))

      val result = service.createSubscription(userId, "SUPPORTER", "MONTHLY", "STRIPE").futureValue
      result.isRight mustBe true
      result.toOption.get.amountCents mustBe 200
      result.toOption.get.patronTier mustBe "SUPPORTER"
    }

    "create a yearly sustainer subscription" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(None))
      when(mockRepo.create(any[PatronSubscription]))
        .thenAnswer(inv => Future.successful(inv.getArgument[PatronSubscription](0).copy(id = Some(2))))

      val result = service.createSubscription(userId, "SUSTAINER", "YEARLY", "STRIPE").futureValue
      result.isRight mustBe true
      result.toOption.get.amountCents mustBe 10000
    }

    "reject invalid tier" in {
      val result = service.createSubscription(userId, "INVALID", "MONTHLY", "STRIPE").futureValue
      result mustBe Left("Invalid patron tier: INVALID")
      verify(mockRepo, never()).findActiveByUserId(any())
    }

    "reject invalid billing interval" in {
      val result = service.createSubscription(userId, "SUPPORTER", "WEEKLY", "STRIPE").futureValue
      result mustBe Left("Invalid billing interval: WEEKLY")
    }

    "reject invalid payment provider" in {
      val result = service.createSubscription(userId, "SUPPORTER", "MONTHLY", "BITCOIN").futureValue
      result mustBe Left("Invalid payment provider: BITCOIN")
    }

    "reject when user already has active subscription" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(Some(testSubscription)))

      val result = service.createSubscription(userId, "CONTRIBUTOR", "MONTHLY", "STRIPE").futureValue
      result.isLeft mustBe true
      result.swap.toOption.get must include("already has an active subscription")
    }

    "set correct period end for monthly" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(None))
      when(mockRepo.create(any[PatronSubscription]))
        .thenAnswer(inv => Future.successful(inv.getArgument[PatronSubscription](0).copy(id = Some(3))))

      val result = service.createSubscription(userId, "SUPPORTER", "MONTHLY", "STRIPE").futureValue
      val sub = result.toOption.get
      sub.currentPeriodStart mustBe defined
      sub.currentPeriodEnd mustBe defined
    }
  }

  "PatronageService.cancelSubscription" should {

    "cancel an active subscription" in {
      when(mockRepo.findById(1)).thenReturn(Future.successful(Some(testSubscription)))
      when(mockRepo.cancel(1)).thenReturn(Future.successful(true))

      val result = service.cancelSubscription(1, userId).futureValue
      result mustBe Right(true)
    }

    "reject cancellation of non-existent subscription" in {
      when(mockRepo.findById(999)).thenReturn(Future.successful(None))

      val result = service.cancelSubscription(999, userId).futureValue
      result mustBe Left("Subscription not found")
    }

    "reject cancellation by wrong user" in {
      val otherUserId = UUID.randomUUID()
      when(mockRepo.findById(1)).thenReturn(Future.successful(Some(testSubscription)))

      val result = service.cancelSubscription(1, otherUserId).futureValue
      result mustBe Left("Not authorized to cancel this subscription")
    }

    "reject cancellation of already cancelled subscription" in {
      val cancelled = testSubscription.copy(status = "CANCELLED")
      when(mockRepo.findById(1)).thenReturn(Future.successful(Some(cancelled)))

      val result = service.cancelSubscription(1, userId).futureValue
      result.isLeft mustBe true
      result.swap.toOption.get must include("Cannot cancel")
    }
  }

  "PatronageService.handlePaymentWebhook" should {

    "handle subscription renewal" in {
      when(mockRepo.findByProviderSubscriptionId("STRIPE", "sub_123"))
        .thenReturn(Future.successful(Some(testSubscription)))
      when(mockRepo.updateStatus(1, "ACTIVE")).thenReturn(Future.successful(true))
      when(mockRepo.updatePeriod(meq(1), any[LocalDateTime], any[LocalDateTime]))
        .thenReturn(Future.successful(true))

      val event = WebhookEvent(
        eventType = "subscription.renewed",
        providerSubscriptionId = "sub_123",
        periodStart = Some(now),
        periodEnd = Some(now.plusMonths(1))
      )

      val result = service.handlePaymentWebhook(event, "STRIPE").futureValue
      result mustBe Right(true)
    }

    "handle subscription cancellation" in {
      when(mockRepo.findByProviderSubscriptionId("STRIPE", "sub_123"))
        .thenReturn(Future.successful(Some(testSubscription)))
      when(mockRepo.cancel(1)).thenReturn(Future.successful(true))

      val event = WebhookEvent(
        eventType = "subscription.cancelled",
        providerSubscriptionId = "sub_123"
      )

      val result = service.handlePaymentWebhook(event, "STRIPE").futureValue
      result mustBe Right(true)
    }

    "handle payment failure" in {
      when(mockRepo.findByProviderSubscriptionId("STRIPE", "sub_123"))
        .thenReturn(Future.successful(Some(testSubscription)))
      when(mockRepo.updateStatus(1, "PAST_DUE")).thenReturn(Future.successful(true))

      val event = WebhookEvent(
        eventType = "invoice.payment_failed",
        providerSubscriptionId = "sub_123"
      )

      val result = service.handlePaymentWebhook(event, "STRIPE").futureValue
      result mustBe Right(true)
    }

    "return error for unknown subscription" in {
      when(mockRepo.findByProviderSubscriptionId("STRIPE", "sub_unknown"))
        .thenReturn(Future.successful(None))

      val event = WebhookEvent(
        eventType = "subscription.renewed",
        providerSubscriptionId = "sub_unknown"
      )

      val result = service.handlePaymentWebhook(event, "STRIPE").futureValue
      result mustBe Left("Subscription not found")
    }
  }

  "PatronageService.expireOverdueSubscriptions" should {

    "expire subscriptions past their period end" in {
      val expired = testSubscription.copy(
        currentPeriodEnd = Some(now.minusDays(1))
      )
      when(mockRepo.findByStatus("ACTIVE")).thenReturn(Future.successful(Seq(expired)))
      when(mockRepo.updateStatus(1, "EXPIRED")).thenReturn(Future.successful(true))

      val result = service.expireOverdueSubscriptions().futureValue
      result mustBe 1
    }

    "not expire subscriptions within their period" in {
      val current = testSubscription.copy(
        currentPeriodEnd = Some(now.plusDays(15))
      )
      when(mockRepo.findByStatus("ACTIVE")).thenReturn(Future.successful(Seq(current)))

      val result = service.expireOverdueSubscriptions().futureValue
      result mustBe 0
    }
  }

  "PatronageService.getPatronSummary" should {

    "compute summary with active patrons" in {
      when(mockRepo.countActive()).thenReturn(Future.successful(10))
      when(mockRepo.countByTier()).thenReturn(Future.successful(
        Map("SUPPORTER" -> 5, "CONTRIBUTOR" -> 3, "SUSTAINER" -> 2)
      ))

      val result = service.getPatronSummary.futureValue
      result.activePatrons mustBe 10
      result.tierCounts("SUPPORTER") mustBe 5
      // Monthly revenue: 5*200 + 3*500 + 2*1000 = 1000 + 1500 + 2000 = 4500
      result.monthlyRevenueCents mustBe 4500
    }

    "handle empty patron base" in {
      when(mockRepo.countActive()).thenReturn(Future.successful(0))
      when(mockRepo.countByTier()).thenReturn(Future.successful(Map.empty))

      val result = service.getPatronSummary.futureValue
      result.activePatrons mustBe 0
      result.monthlyRevenueCents mustBe 0
    }
  }

  "PatronageService.isPatron" should {

    "return true for active patron" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(Some(testSubscription)))
      service.isPatron(userId).futureValue mustBe true
    }

    "return false for non-patron" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(None))
      service.isPatron(userId).futureValue mustBe false
    }
  }

  "PatronageService.getPatronTier" should {

    "return tier for active patron" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(Some(testSubscription)))
      service.getPatronTier(userId).futureValue mustBe Some("SUPPORTER")
    }

    "return None for non-patron" in {
      when(mockRepo.findActiveByUserId(userId)).thenReturn(Future.successful(None))
      service.getPatronTier(userId).futureValue mustBe None
    }
  }
}
