package controllers

import org.webjars.play.WebJarsUtil
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}
import services.PublicationService

import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class PublicationController @Inject()(val controllerComponents: ControllerComponents, publicationService: PublicationService)
                                     (using webJarsUtil: WebJarsUtil, ec: ExecutionContext) extends BaseController with I18nSupport {
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.references())
  }


  def getAllPublicationsWithDetailsJson(): Action[play.api.mvc.AnyContent] = Action.async {
    publicationService.getAllPublicationsWithDetails().map { publicationsWithDetails =>
      Ok(Json.toJson(publicationsWithDetails))
    }
  }

  def getAllPublicationsWithDetailsHtml(): Action[play.api.mvc.AnyContent] = Action.async {
    publicationService.getAllPublicationsWithDetails().map { publicationsWithDetails =>
      Ok(views.html.publicationList(publicationsWithDetails))
    }
  }
}