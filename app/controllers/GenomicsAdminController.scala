package controllers

import actors.YBrowseVariantUpdateActor
import actors.YBrowseVariantUpdateActor.{RunUpdate, UpdateResult}
import jakarta.inject.{Inject, Named, Singleton}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import play.api.Logging
import play.api.libs.json.{Json, OWrites}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.AuthService

import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GenomicsAdminController @Inject()(
  val controllerComponents: ControllerComponents,
  authService: AuthService,
  @Named("ybrowse-variant-update-actor") ybrowseUpdateActor: ActorRef
)(implicit ec: ExecutionContext) extends BaseController with Logging {

  implicit val timeout: Timeout = Timeout(10.minutes)

  implicit val updateResultWrites: OWrites[UpdateResult] = Json.writes[UpdateResult]

  /**
   * Check if current user has Admin role.
   */
  private def withAdminAuth[A](request: play.api.mvc.Request[A])(
    block: UUID => Future[play.api.mvc.Result]
  ): Future[play.api.mvc.Result] = {
    implicit val req: play.api.mvc.RequestHeader = request
    request.session.get("userId").map(UUID.fromString) match {
      case Some(userId) =>
        authService.hasRole(userId, "Admin").flatMap {
          case true => block(userId)
          case false =>
            Future.successful(
              Forbidden(Json.obj("error" -> "You do not have permission to access this endpoint."))
            )
        }
      case None =>
        Future.successful(
          Unauthorized(Json.obj("error" -> "Authentication required."))
        )
    }
  }

  /**
   * Trigger on-demand YBrowse variant update.
   * Only accessible by users with Admin role.
   */
  def triggerYBrowseUpdate(): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { adminUserId =>
      logger.info(s"Admin $adminUserId triggered YBrowse variant update")

      (ybrowseUpdateActor ? RunUpdate).mapTo[UpdateResult].map { result =>
        if (result.success) {
          Ok(Json.toJson(result))
        } else {
          InternalServerError(Json.toJson(result))
        }
      }.recover {
        case ex: Exception =>
          logger.error(s"YBrowse update request failed: ${ex.getMessage}", ex)
          InternalServerError(Json.obj(
            "success" -> false,
            "variantsIngested" -> 0,
            "message" -> s"Request failed: ${ex.getMessage}"
          ))
      }
    }
  }
}
