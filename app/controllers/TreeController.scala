package controllers

import org.webjars.play.WebJarsUtil
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}

import javax.inject.*

@Singleton
class TreeController @Inject()(val controllerComponents: ControllerComponents)
                              (using webJarsUtil: WebJarsUtil) extends BaseController {
  def ytree(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.ytree())
  }

  def mtree(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.mtree())
  }
  
  def apiYTree(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(Json.toJson(Map("status" -> "ok")))
  }

  def apiMTree(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(Json.toJson(Map("status" -> "ok")))
  }
}