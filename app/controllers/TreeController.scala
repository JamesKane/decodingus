package controllers

import models.HaplogroupType
import models.HaplogroupType.{MT, Y}
import models.api.{SubcladeDTO, TreeNodeDTO}
import models.view.TreeViewModel
import org.webjars.play.WebJarsUtil
import play.api.cache.{AsyncCacheApi, Cached}
import play.api.libs.json.Json
import play.api.mvc.*
import services.{ApiRoute, FragmentRoute, HaplogroupTreeService}

import javax.inject.*
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

/**
 * Controller responsible for handling actions related to haplogroup trees.
 * Provides routes to render tree views and API endpoints to retrieve tree data.
 *
 * @constructor Creates a new TreeController.
 * @param controllerComponents Essential components required for Play controllers.
 * @param treeService          Service responsible for building haplogroup tree responses.
 * @param webJarsUtil          Utility for managing web resources.
 * @param ec                   Execution context for handling asynchronous operations.
 */
@Singleton
class TreeController @Inject()(val controllerComponents: ControllerComponents,
                               treeService: HaplogroupTreeService,
                               cached: Cached,
                               cache: AsyncCacheApi)
                              (using webJarsUtil: WebJarsUtil, ec: ExecutionContext)
  extends BaseController {

  /**
   * Configuration for initializing and handling tree-based data structures
   * specific to genetic haplogroup analysis.
   *
   * @param haplogroupType Specifies the type of haplogroup classification,
   *                       either paternal (Y) or maternal (MT).
   * @param defaultRoot    Represents the default root haplogroup to be used
   *                       when rendering or querying the tree structure.
   */
  private case class TreeConfig(
                                 haplogroupType: HaplogroupType,
                                 defaultRoot: String
                               )

  private val YConfig = TreeConfig(Y, "Y")
  private val MTConfig = TreeConfig(MT, "L")

  /**
   * Renders the Y-DNA tree page.
   *
   * This action responds to HTTP GET requests and renders a view that displays
   * the Y-DNA haplogroup tree. The view includes interactive elements such as
   * a search form for navigating to specific haplogroups.
   *
   * @return an action that renders the Y-DNA tree page as an HTML response
   */
  def ytree(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.ytree())
  }

  /**
   * Renders the MT-DNA tree page.
   *
   * This action responds to HTTP GET requests and renders a view that displays
   * the MT-DNA haplogroup tree. The view includes interactive elements such as
   * a search form for navigating to specific haplogroups.
   *
   * @return an action that renders the MT-DNA tree page as an HTML response
   */
  def mtree(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.mtree())
  }

  /**
   * Handles API requests to retrieve the Y-DNA haplogroup tree structure.
   *
   * This method generates a JSON representation of the Y-DNA haplogroup tree
   * starting from a specified root haplogroup. If no root haplogroup is provided,
   * it defaults to the configuration's default root haplogroup.
   *
   * @param rootHaplogroup an optional string representing the root haplogroup
   *                       for the Y-DNA tree. If None, the default root is used.
   * @return an Action that produces a JSON response containing the Y-DNA haplogroup tree.
   */
  def apiYTree(rootHaplogroup: Option[String]): EssentialAction =
    cached.status(
      (request: RequestHeader) => s"ytree-${rootHaplogroup.getOrElse("all")}",
      200,
      24.hours
    ) {
    treeAction(rootHaplogroup, YConfig, ApiRoute)
  }

  /**
   * Handles API requests to retrieve the MT-DNA haplogroup tree structure.
   *
   * This method generates a JSON representation of the MT-DNA haplogroup tree
   * starting from a specified root haplogroup. If no root haplogroup is provided,
   * it defaults to the configuration's default root haplogroup.
   *
   * @param rootHaplogroup an optional string representing the root haplogroup
   *                       for the MT-DNA tree. If None, the default root is used.
   * @return an Action that produces a JSON response containing the MT-DNA haplogroup tree.
   */
  def apiMTree(rootHaplogroup: Option[String]): EssentialAction =
    cached.status(
      (request: RequestHeader) => s"mtree-${rootHaplogroup.getOrElse("all")}",
      200,
      24.hours
    ) {
      treeAction(rootHaplogroup, MTConfig, ApiRoute)
    }

  /**
   * Handles requests to render a fragment of the Y-DNA haplogroup tree.
   *
   * This method generates an HTML fragment that represents a specific portion of the Y-DNA haplogroup tree.
   * The portion of the tree rendered can be controlled by specifying a root haplogroup. If no root haplogroup
   * is provided, the configuration's default root is used.
   *
   * @param rootHaplogroup an optional string indicating the root haplogroup for the Y-DNA tree fragment.
   *                       If None, the default root haplogroup is used.
   * @return an Action that produces an HTML response containing the Y-DNA tree fragment.
   */
  def yTreeFragment(rootHaplogroup: Option[String]): EssentialAction =
    cached.status(
      (request: RequestHeader) => s"ytree-fragment-${rootHaplogroup.getOrElse("all")}",
      200,
      24.hours
    ) {
      treeAction(rootHaplogroup, YConfig, FragmentRoute)
    }

  /**
   * Handles requests to render a fragment of the MT-DNA haplogroup tree.
   *
   * This method generates an HTML fragment that represents a specific portion of the MT-DNA haplogroup tree.
   * The portion of the tree rendered can be controlled by specifying a root haplogroup. If no root haplogroup
   * is provided, the configuration's default root is used.
   *
   * @param rootHaplogroup an optional string indicating the root haplogroup for the MT-DNA tree fragment.
   *                       If None, the default root haplogroup is used.
   * @return an Action that produces an HTML response containing the MT-DNA tree fragment.
   */
  def mTreeFragment(rootHaplogroup: Option[String]): EssentialAction =
    cached.status(
      (request: RequestHeader) => s"mtree-fragment-${rootHaplogroup.getOrElse("all")}",
      200,
      24.hours
    ) {
      treeAction(rootHaplogroup, MTConfig, FragmentRoute)
    }

  /**
   * Generates a tree structure for a given root haplogroup and renders it as either
   * a JSON response or an HTML fragment depending on the specified route type.
   *
   * This is where TreeLayoutService is now called for FragmentRoute responses.
   *
   * @param rootHaplogroup an optional string specifying the root haplogroup
   * for the tree. If None, the default root defined in
   * the configuration is used.
   * @param config         the tree configuration containing settings such
   * as the default root and haplogroup type.
   * @param routeType      the type of response to generate, either JSON
   * (for API responses) or HTML fragments.
   * @return an Action that produces either a JSON response with the tree
   * structure or an HTML fragment based on the route type.
   */
  private def treeAction(
                          rootHaplogroup: Option[String],
                          config: TreeConfig,
                          routeType: services.RouteType
                        ): Action[AnyContent] = Action.async { implicit request =>

    val haplogroupName = rootHaplogroup.getOrElse(config.defaultRoot)
    val isAbsoluteTopRootView = haplogroupName == config.defaultRoot

    treeService.buildTreeResponse(haplogroupName, config.haplogroupType, routeType)
      .map { treeDto =>
        routeType match {
          case ApiRoute =>
            // TAPIR can't deal with the recursive tree, so we need to flatten it.
            val apiBody: Seq[SubcladeDTO] = mapApiResponse(treeDto.subclade)
            Ok(Json.toJson(apiBody))
          case FragmentRoute =>
            val treeViewModel: Option[TreeViewModel] = treeDto.subclade.flatMap { rootNodeDTO =>
              services.TreeLayoutService.layoutTree(treeDto, isAbsoluteTopRootView)
            }

            Ok(views.html.fragments.haplogroup(treeDto, config.haplogroupType, treeViewModel))
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

  def getSnpDetailSidebar(haplogroupName: String, haplogroupType: HaplogroupType): Action[AnyContent] = Action.async { (request: Request[AnyContent]) =>
    treeService.findVariantsForHaplogroup(haplogroupName, haplogroupType).map { snps =>
      Ok(views.html.fragments.snpDetailSidebar(haplogroupName, snps))
    }
  }

  def emptySnpDetailSidebarPlaceholder: Action[AnyContent] = Action {
    Ok(<div id="snpDetailSidebarPlaceholder"></div>)
  }

  // TODO: Should probably move this to the service.
  private def mapApiResponse(root: Option[TreeNodeDTO]): Seq[SubcladeDTO] = {
    def map(node: TreeNodeDTO, parent: Option[TreeNodeDTO]): Seq[SubcladeDTO] = {
      SubcladeDTO(node.name, parent.map(_.name), node.variants, node.updated, node.isBackbone) +: node.children.flatMap(c => map(c, Option(node)))
    }

    root.map(x => map(x, None))
      .getOrElse(Seq())
  }
}
