package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.*
import services.{PatronageService, WebhookEvent}

import java.util.UUID
import scala.concurrent.ExecutionContext

@Singleton
class PatronageApiController @Inject()(
                                        val controllerComponents: ControllerComponents,
                                        secureApi: ApiSecurityAction,
                                        patronageService: PatronageService
                                      )(implicit ec: ExecutionContext) extends BaseController with Logging {

  case class CreateSubscriptionRequest(
                                        userId: UUID,
                                        tier: String,
                                        billingInterval: String,
                                        paymentProvider: String,
                                        providerSubscriptionId: Option[String] = None,
                                        providerCustomerId: Option[String] = None
                                      )
  object CreateSubscriptionRequest { implicit val format: OFormat[CreateSubscriptionRequest] = Json.format }

  case class CancelSubscriptionRequest(userId: UUID)
  object CancelSubscriptionRequest { implicit val format: OFormat[CancelSubscriptionRequest] = Json.format }

  def createSubscription(): Action[CreateSubscriptionRequest] =
    secureApi.jsonAction[CreateSubscriptionRequest].async { request =>
      val r = request.body
      patronageService.createSubscription(
        userId = r.userId,
        tier = r.tier,
        billingInterval = r.billingInterval,
        paymentProvider = r.paymentProvider,
        providerSubscriptionId = r.providerSubscriptionId,
        providerCustomerId = r.providerCustomerId
      ).map {
        case Right(sub) => Created(Json.toJson(sub))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def cancelSubscription(id: Int): Action[CancelSubscriptionRequest] =
    secureApi.jsonAction[CancelSubscriptionRequest].async { request =>
      patronageService.cancelSubscription(id, request.body.userId).map {
        case Right(_) => Ok(Json.obj("cancelled" -> true))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def getSubscription(userId: UUID): Action[AnyContent] = secureApi.async { _ =>
    patronageService.getActiveSubscription(userId).map {
      case Some(sub) => Ok(Json.toJson(sub))
      case None => NotFound(Json.obj("error" -> "No active subscription"))
    }
  }

  def getUserSubscriptions(userId: UUID): Action[AnyContent] = secureApi.async { _ =>
    patronageService.getUserSubscriptions(userId).map { subs =>
      Ok(Json.obj("subscriptions" -> subs, "total" -> subs.size))
    }
  }

  def isPatron(userId: UUID): Action[AnyContent] = secureApi.async { _ =>
    patronageService.isPatron(userId).map { isPatron =>
      Ok(Json.obj("isPatron" -> isPatron))
    }
  }

  def getPatronSummary: Action[AnyContent] = secureApi.async { _ =>
    patronageService.getPatronSummary.map(summary => Ok(Json.toJson(summary)))
  }

  def expireOverdue(): Action[AnyContent] = secureApi.async { _ =>
    patronageService.expireOverdueSubscriptions().map { count =>
      Ok(Json.obj("expired" -> count))
    }
  }
}
