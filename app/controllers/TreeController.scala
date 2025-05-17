package controllers

import models.HaplogroupType.{MT, Y}
import org.webjars.play.WebJarsUtil
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}
import services.HaplogroupTreeService

import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class TreeController @Inject()(val controllerComponents: ControllerComponents,
                               treeService: HaplogroupTreeService
                              )
                              (using webJarsUtil: WebJarsUtil, ec: ExecutionContext
                              ) extends BaseController {
  def ytree(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.ytree())
  }

  def mtree(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.mtree())
  }

  def apiYTree(rootHaplogroup: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    rootHaplogroup match {
      case Some(name) =>
        treeService.buildTreeResponse(name, Y).map(tree => Ok(Json.toJson(tree)))
          .recover {
            case _: IllegalArgumentException => NotFound(Json.obj("error" -> s"Haplogroup $name not found"))
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
      case None =>
        // Default to Y-DNA root haplogroup (typically "Y")
        treeService.buildTreeResponse("Y", Y).map(tree => Ok(Json.toJson(tree)))
    }
  }

  def apiMTree(rootHaplogroup: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    rootHaplogroup match {
      case Some(name) =>
        treeService.buildTreeResponse(name, MT).map(tree => Ok(Json.toJson(tree)))
          .recover {
            case _: IllegalArgumentException => NotFound(Json.obj("error" -> s"Haplogroup $name not found"))
            case e => InternalServerError(Json.obj("error" -> e.getMessage))
          }
      case None =>
        // Default to mtDNA root haplogroup (typically "L")
        treeService.buildTreeResponse("L", MT).map(tree => Ok(Json.toJson(tree)))
    }
  }

}