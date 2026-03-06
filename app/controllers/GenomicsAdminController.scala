package controllers

import actors.YBrowseVariantUpdateActor
import actors.YBrowseVariantUpdateActor.{RunUpdate, UpdateResult}
import actions.{AuthenticatedAction, RoleAction}
import jakarta.inject.{Inject, Named, Singleton}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.libs.json.{Json, OWrites}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GenomicsAdminController @Inject()(
  val controllerComponents: ControllerComponents,
  authenticatedAction: AuthenticatedAction,
  roleAction: RoleAction,
  @Named("ybrowse-variant-update-actor") ybrowseUpdateActor: ActorRef,
  hipstrService: services.genomics.HipStrReferenceIngestionService,
  regionIngestionService: services.genomics.GenomeRegionIngestionService
)(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController with Logging with I18nSupport {

  implicit val timeout: Timeout = Timeout(10.minutes)

  implicit val updateResultWrites: OWrites[UpdateResult] = Json.writes[UpdateResult]

  private def AdminAction = authenticatedAction andThen roleAction("Admin")

  /**
   * Admin dashboard for genomics operations.
   */
  def dashboard(): Action[AnyContent] = AdminAction.async { implicit request =>
    Future.successful(Ok(views.html.admin.genomics.dashboard()))
  }

  /**
   * Trigger on-demand HipSTR reference update.
   */
  def triggerHipStrUpdate(): Action[AnyContent] = AdminAction.async { implicit request =>
    logger.info(s"Admin ${request.user.id.get} triggered HipSTR reference update")

    // Run in background
    hipstrService.bootstrap().onComplete {
      case scala.util.Success(count) => logger.info(s"HipSTR update completed: $count variants")
      case scala.util.Failure(e) => logger.error(s"HipSTR update failed", e)
    }

    Future.successful(Ok(Json.obj("message" -> "HipSTR update started")))
  }

  /**
   * Trigger on-demand Genome Regions bootstrap.
   */
  def triggerRegionsBootstrap(): Action[AnyContent] = AdminAction.async { implicit request =>
    logger.info(s"Admin ${request.user.id.get} triggered Genome Regions bootstrap")

    // Run in background
    regionIngestionService.bootstrap().onComplete {
      case scala.util.Success(_) => logger.info(s"Genome Regions bootstrap completed successfully")
      case scala.util.Failure(e) => logger.error(s"Genome Regions bootstrap failed", e)
    }

    Future.successful(Ok(Json.obj("message" -> "Regions bootstrap started")))
  }

  /**
   * Trigger on-demand YBrowse variant update.
   * Only accessible by users with Admin role.
   * Fire-and-forget: returns immediately, job runs in background.
   */
  def triggerYBrowseUpdate(): Action[AnyContent] = AdminAction.async { implicit request =>
    logger.info(s"Admin ${request.user.id.get} triggered YBrowse variant update")

    // Use a short timeout just to check if job started or was rejected
    implicit val shortTimeout: Timeout = Timeout(5.seconds)

    (ybrowseUpdateActor ? RunUpdate).mapTo[UpdateResult].map { result =>
      if (result.message.contains("already in progress")) {
        Ok(Json.toJson(result))
      } else if (result.success) {
        Ok(Json.toJson(result))
      } else {
        InternalServerError(Json.toJson(result))
      }
    }.recover {
      case _: org.apache.pekko.pattern.AskTimeoutException =>
        Ok(Json.obj(
          "success" -> true,
          "variantsIngested" -> 0,
          "message" -> "Update started. Monitor server logs for progress."
        ))
      case ex: Exception =>
        logger.error("YBrowse update request failed", ex)
        InternalServerError(Json.obj(
          "success" -> false,
          "variantsIngested" -> 0,
          "message" -> "An internal error occurred while starting the update."
        ))
    }
  }
}
