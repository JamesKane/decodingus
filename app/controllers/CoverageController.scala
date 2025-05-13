package controllers

import jakarta.inject.Singleton
import org.webjars.play.WebJarsUtil
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}

import javax.inject.Inject

@Singleton
class CoverageController @Inject()(val controllerComponents: ControllerComponents)
                                  (using webJarsUtil: WebJarsUtil) extends BaseController  {

  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.coverage())
  }

  def apiBenchmarks(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(Json.toJson(Map("status" -> "ok")))
  }
}
