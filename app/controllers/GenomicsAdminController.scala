package controllers

import actors.YBrowseVariantUpdateActor
import actors.YBrowseVariantUpdateActor.{RunUpdate, UpdateResult}
import jakarta.inject.{Inject, Named, Singleton}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.i18n.I18nSupport
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
  @Named("ybrowse-variant-update-actor") ybrowseUpdateActor: ActorRef,
  hipstrService: services.genomics.HipStrReferenceIngestionService,
  regionIngestionService: services.genomics.GenomeRegionIngestionService
)(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController with Logging with I18nSupport {

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
   * Admin dashboard for genomics operations.
   */
  def dashboard(): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { _ =>
      Future.successful(Ok(views.html.admin.genomics.dashboard()))
    }
  }

  /**
   * Trigger on-demand HipSTR reference update.
   */
  def triggerHipStrUpdate(): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { adminUserId =>
      logger.info(s"Admin $adminUserId triggered HipSTR reference update")
      
      // Run in background
      hipstrService.bootstrap().onComplete {
        case scala.util.Success(count) => logger.info(s"HipSTR update completed: $count variants")
        case scala.util.Failure(e) => logger.error(s"HipSTR update failed", e)
      }
      
      Future.successful(Ok(Json.obj("message" -> "HipSTR update started")))
    }
  }

  /**
   * Trigger on-demand Genome Regions bootstrap.
   */
  def triggerRegionsBootstrap(): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { adminUserId =>
      logger.info(s"Admin $adminUserId triggered Genome Regions bootstrap")
      
      // Run in background
      regionIngestionService.bootstrap().onComplete {
        case scala.util.Success(_) => logger.info(s"Genome Regions bootstrap completed successfully")
        case scala.util.Failure(e) => logger.error(s"Genome Regions bootstrap failed", e)
      }
      
      Future.successful(Ok(Json.obj("message" -> "Regions bootstrap started")))
    }
  }

  /**
   * Trigger on-demand YBrowse variant update.
   * Only accessible by users with Admin role.
   * Fire-and-forget: returns immediately, job runs in background.
   */
  def triggerYBrowseUpdate(): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { adminUserId =>
      logger.info(s"Admin $adminUserId triggered YBrowse variant update")

      // Use a short timeout just to check if job started or was rejected
      implicit val shortTimeout: Timeout = Timeout(5.seconds)

      (ybrowseUpdateActor ? RunUpdate).mapTo[UpdateResult].map { result =>
        // If we get a quick response, it's either a rejection (already running) or an error
        if (result.message.contains("already in progress")) {
          Ok(Json.toJson(result))
        } else if (result.success) {
          Ok(Json.toJson(result))
        } else {
          InternalServerError(Json.toJson(result))
        }
      }.recover {
        case _: org.apache.pekko.pattern.AskTimeoutException =>
          // Timeout means job started successfully and is running
          Ok(Json.obj(
            "success" -> true,
            "variantsIngested" -> 0,
            "message" -> "Update started. Monitor server logs for progress."
          ))
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
