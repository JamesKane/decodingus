package controllers

import org.webjars.play.WebJarsUtil
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}

import javax.inject.*

@Singleton
class ReferencesController @Inject()(val controllerComponents: ControllerComponents)
                                     (using webJarsUtil: WebJarsUtil) extends BaseController {
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.references())
  }
}