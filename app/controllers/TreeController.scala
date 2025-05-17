package controllers

import models.HaplogroupType
import models.HaplogroupType.{MT, Y}
import org.webjars.play.WebJarsUtil
import play.api.libs.json.Json
import play.api.mvc.*
import services.{ApiRoute, FragmentRoute, HaplogroupTreeService}

import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class TreeController @Inject()(val controllerComponents: ControllerComponents,
                               treeService: HaplogroupTreeService)
                              (using webJarsUtil: WebJarsUtil, ec: ExecutionContext)
  extends BaseController {

  private case class TreeConfig(
                                 haplogroupType: HaplogroupType,
                                 defaultRoot: String
                               )

  private val YConfig = TreeConfig(Y, "Y")
  private val MTConfig = TreeConfig(MT, "L")

  def ytree(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.ytree())
  }

  def mtree(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.mtree())
  }

  def apiYTree(rootHaplogroup: Option[String]): Action[AnyContent] =
    treeAction(rootHaplogroup, YConfig, ApiRoute)

  def apiMTree(rootHaplogroup: Option[String]): Action[AnyContent] =
    treeAction(rootHaplogroup, MTConfig, ApiRoute)

  def yTreeFragment(rootHaplogroup: Option[String]): Action[AnyContent] =
    treeAction(rootHaplogroup, YConfig, FragmentRoute)

  def mTreeFragment(rootHaplogroup: Option[String]): Action[AnyContent] =
    treeAction(rootHaplogroup, MTConfig, FragmentRoute)

  private def treeAction(
                          rootHaplogroup: Option[String],
                          config: TreeConfig,
                          routeType: services.RouteType
                        ): Action[AnyContent] = Action.async { implicit request =>

    val haplogroupName = rootHaplogroup.getOrElse(config.defaultRoot)

    treeService.buildTreeResponse(haplogroupName, config.haplogroupType, routeType)
      .map { tree =>
        routeType match {
          case ApiRoute => Ok(Json.toJson(tree))
          case FragmentRoute => Ok(views.html.fragments.haplogroup(tree, config.haplogroupType))
        }
      }
      .recover {
        case _: IllegalArgumentException =>
          routeType match {
            case ApiRoute => NotFound(Json.obj("error" -> s"Haplogroup $haplogroupName not found"))
            case FragmentRoute => Ok(views.html.fragments.error(s"Haplogroup $haplogroupName not found"))
          }
        case e =>
          routeType match {
            case ApiRoute => InternalServerError(Json.obj("error" -> e.getMessage))
            case FragmentRoute => Ok(views.html.fragments.error(e.getMessage))
          }
      }
  }
}
