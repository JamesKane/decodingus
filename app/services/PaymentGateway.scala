package services

import scala.concurrent.Future

case class CheckoutRequest(
                            tier: String,
                            billingInterval: String,
                            amountCents: Int,
                            currency: String = "USD",
                            customerEmail: Option[String] = None,
                            successUrl: String,
                            cancelUrl: String
                          )

case class CheckoutResult(
                           sessionId: String,
                           checkoutUrl: String,
                           providerCustomerId: Option[String] = None
                         )

case class WebhookEvent(
                         eventType: String,
                         providerSubscriptionId: String,
                         providerCustomerId: Option[String] = None,
                         status: Option[String] = None,
                         periodStart: Option[java.time.LocalDateTime] = None,
                         periodEnd: Option[java.time.LocalDateTime] = None
                       )

trait PaymentGateway {
  def providerName: String
  def createCheckoutSession(request: CheckoutRequest): Future[CheckoutResult]
  def cancelSubscription(providerSubscriptionId: String): Future[Boolean]
  def parseWebhookEvent(payload: String, signature: String): Future[Option[WebhookEvent]]
}
