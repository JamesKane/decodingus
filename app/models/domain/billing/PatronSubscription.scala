package models.domain.billing

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime
import java.util.UUID

case class PatronSubscription(
                               id: Option[Int] = None,
                               userId: UUID,
                               patronTier: String,
                               status: String = "ACTIVE",
                               paymentProvider: String,
                               providerSubscriptionId: Option[String] = None,
                               providerCustomerId: Option[String] = None,
                               amountCents: Int,
                               currency: String = "USD",
                               billingInterval: String,
                               currentPeriodStart: Option[LocalDateTime] = None,
                               currentPeriodEnd: Option[LocalDateTime] = None,
                               cancelledAt: Option[LocalDateTime] = None,
                               createdAt: LocalDateTime = LocalDateTime.now(),
                               updatedAt: LocalDateTime = LocalDateTime.now()
                             )

object PatronSubscription {
  implicit val format: OFormat[PatronSubscription] = Json.format[PatronSubscription]

  val ValidTiers: Set[String] = Set("SUPPORTER", "CONTRIBUTOR", "SUSTAINER", "FOUNDING_PATRON")
  val ValidStatuses: Set[String] = Set("ACTIVE", "CANCELLED", "PAST_DUE", "EXPIRED")
  val ValidProviders: Set[String] = Set("STRIPE", "PAYPAL")
  val ValidIntervals: Set[String] = Set("MONTHLY", "YEARLY")
}

object PatronTier {
  val Supporter = "SUPPORTER"
  val Contributor = "CONTRIBUTOR"
  val Sustainer = "SUSTAINER"
  val FoundingPatron = "FOUNDING_PATRON"

  def amountCents(tier: String, interval: String): Int = (tier, interval) match {
    case (Supporter, "MONTHLY") => 200
    case (Supporter, "YEARLY") => 2000
    case (Contributor, "MONTHLY") => 500
    case (Contributor, "YEARLY") => 5000
    case (Sustainer, "MONTHLY") => 1000
    case (Sustainer, "YEARLY") => 10000
    case (FoundingPatron, "MONTHLY") => 5000
    case (FoundingPatron, "YEARLY") => 50000
    case _ => throw new IllegalArgumentException(s"Unknown tier/interval: $tier/$interval")
  }

  def displayName(tier: String): String = tier match {
    case Supporter => "Supporter"
    case Contributor => "Contributor"
    case Sustainer => "Sustainer"
    case FoundingPatron => "Founding Patron"
    case other => other
  }
}

case class PatronSummary(
                          activePatrons: Int,
                          tierCounts: Map[String, Int],
                          monthlyRevenueCents: Int
                        )

object PatronSummary {
  implicit val format: OFormat[PatronSummary] = Json.format[PatronSummary]
}
