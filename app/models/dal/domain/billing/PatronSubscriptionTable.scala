package models.dal.domain.billing

import models.dal.MyPostgresProfile.api.*
import models.domain.billing.PatronSubscription
import slick.lifted.{ProvenShape, Tag}

import java.time.LocalDateTime
import java.util.UUID

class PatronSubscriptionTable(tag: Tag) extends Table[PatronSubscription](tag, Some("billing"), "patron_subscription") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def userId = column[UUID]("user_id")
  def patronTier = column[String]("patron_tier")
  def status = column[String]("status")
  def paymentProvider = column[String]("payment_provider")
  def providerSubscriptionId = column[Option[String]]("provider_subscription_id")
  def providerCustomerId = column[Option[String]]("provider_customer_id")
  def amountCents = column[Int]("amount_cents")
  def currency = column[String]("currency")
  def billingInterval = column[String]("billing_interval")
  def currentPeriodStart = column[Option[LocalDateTime]]("current_period_start")
  def currentPeriodEnd = column[Option[LocalDateTime]]("current_period_end")
  def cancelledAt = column[Option[LocalDateTime]]("cancelled_at")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  override def * : ProvenShape[PatronSubscription] = (
    id.?,
    userId,
    patronTier,
    status,
    paymentProvider,
    providerSubscriptionId,
    providerCustomerId,
    amountCents,
    currency,
    billingInterval,
    currentPeriodStart,
    currentPeriodEnd,
    cancelledAt,
    createdAt,
    updatedAt
  ).mapTo[PatronSubscription]
}
