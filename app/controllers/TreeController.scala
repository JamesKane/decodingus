package controllers

import config.FeatureFlags
import models.HaplogroupType
import models.HaplogroupType.{MT, Y}
import models.api.{SubcladeDTO, TreeNodeDTO}
import models.domain.haplogroups.HaplogroupProvenance
import models.view.TreeViewModel
import org.webjars.play.WebJarsUtil
import play.api.cache.{AsyncCacheApi, Cached}
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.*
import services.{ApiRoute, FragmentRoute, HaplogroupTreeService}

import javax.inject.*
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

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
class TreeController @Inject()(val controllerComponents: MessagesControllerComponents,
                               treeService: HaplogroupTreeService,
                               featureFlags: FeatureFlags,
                               cached: Cached,
                               cache: AsyncCacheApi)
                              (using webJarsUtil: WebJarsUtil, ec: ExecutionContext)
  extends BaseController with I18nSupport {

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
  private val BLOCK_LAYOUT_COOKIE = "showBlockLayout"

  private def shouldShowBlockLayout(request: RequestHeader): Boolean = {
    request.cookies.get(BLOCK_LAYOUT_COOKIE).map(_.value.toBoolean).getOrElse(featureFlags.showBlockLayout)
  }

  /**
   * Renders the Y-DNA tree page.
   *
   * This action responds to HTTP GET requests and renders a view that displays
   * the Y-DNA haplogroup tree. The view includes interactive elements such as
   * a search form for navigating to specific haplogroups.
   *
   * @param rootHaplogroup Optional haplogroup to use as the initial root.
   *                       If provided, the tree will load centered on this haplogroup.
   * @return an action that renders the Y-DNA tree page as an HTML response
   */
  def ytree(rootHaplogroup: Option[String]): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.ytree(rootHaplogroup, shouldShowBlockLayout(request)))
  }

  /**
   * Renders the MT-DNA tree page.
   *
   * This action responds to HTTP GET requests and renders a view that displays
   * the MT-DNA haplogroup tree. The view includes interactive elements such as
   * a search form for navigating to specific haplogroups.
   *
   * @param rootHaplogroup Optional haplogroup to use as the initial root.
   *                       If provided, the tree will load centered on this haplogroup.
   * @return an action that renders the MT-DNA tree page as an HTML response
   */
  def mtree(rootHaplogroup: Option[String]): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.mtree(rootHaplogroup, shouldShowBlockLayout(request)))
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
   * For HTMX requests (identified by the HX-Request header), returns the HTML fragment.
   * For direct browser requests (e.g., shared URLs), redirects to the full page with the rootHaplogroup parameter.
   *
   * @param rootHaplogroup an optional string indicating the root haplogroup for the Y-DNA tree fragment.
   *                       If None, the default root haplogroup is used.
   * @return an Action that produces an HTML response containing the Y-DNA tree fragment,
   *         or a redirect to the full page for non-HTMX requests.
   */
  def yTreeFragment(rootHaplogroup: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    if (isHtmxRequest(request)) {
      // HTMX request - return fragment (with caching handled internally)
      cachedTreeFragment(rootHaplogroup, YConfig, s"ytree-fragment-${rootHaplogroup.getOrElse("all")}")
    } else {
      // Direct browser request - redirect to full page
      Future.successful(Redirect(routes.TreeController.ytree(rootHaplogroup)))
    }
  }

  /**
   * Handles requests to render a fragment of the MT-DNA haplogroup tree.
   *
   * This method generates an HTML fragment that represents a specific portion of the MT-DNA haplogroup tree.
   * The portion of the tree rendered can be controlled by specifying a root haplogroup. If no root haplogroup
   * is provided, the configuration's default root is used.
   *
   * For HTMX requests (identified by the HX-Request header), returns the HTML fragment.
   * For direct browser requests (e.g., shared URLs), redirects to the full page with the rootHaplogroup parameter.
   *
   * @param rootHaplogroup an optional string indicating the root haplogroup for the MT-DNA tree fragment.
   *                       If None, the default root haplogroup is used.
   * @return an Action that produces an HTML response containing the MT-DNA tree fragment,
   *         or a redirect to the full page for non-HTMX requests.
   */
  def mTreeFragment(rootHaplogroup: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    if (isHtmxRequest(request)) {
      // HTMX request - return fragment (with caching handled internally)
      cachedTreeFragment(rootHaplogroup, MTConfig, s"mtree-fragment-${rootHaplogroup.getOrElse("all")}")
    } else {
      // Direct browser request - redirect to full page
      Future.successful(Redirect(routes.TreeController.mtree(rootHaplogroup)))
    }
  }

  /**
   * Checks if the request is from HTMX by looking for the HX-Request header.
   */
  private def isHtmxRequest(request: Request[_]): Boolean = {
    request.headers.get("HX-Request").contains("true")
  }

  /**
   * Returns a cached tree fragment response, using the async cache API.
   */
  private def cachedTreeFragment(
                                  rootHaplogroup: Option[String],
                                  config: TreeConfig,
                                  cacheKey: String
                                )(using request: Request[AnyContent]): Future[Result] = {
    val useBlockLayout = shouldShowBlockLayout(request)
    val effectiveCacheKey = s"$cacheKey-block:$useBlockLayout"
    
    cache.getOrElseUpdate(effectiveCacheKey, 24.hours) {
      buildTreeFragment(rootHaplogroup, config, useBlockLayout)
    }
  }

  /**
   * Builds the tree fragment response.
   */
  private def buildTreeFragment(
                                 rootHaplogroup: Option[String],
                                 config: TreeConfig,
                                 showBlockLayout: Boolean
                               )(using request: Request[AnyContent]): Future[Result] = {
    val haplogroupName = rootHaplogroup.getOrElse(config.defaultRoot)
    val isAbsoluteTopRootView = haplogroupName == config.defaultRoot
    
    val orientation = if (showBlockLayout) services.TreeOrientation.Vertical else services.TreeOrientation.Horizontal

    treeService.buildTreeResponse(haplogroupName, config.haplogroupType, FragmentRoute)
      .map { treeDto =>
        val treeViewModel: Option[TreeViewModel] = treeDto.subclade.flatMap { _ =>
          services.TreeLayoutService.layoutTree(treeDto, isAbsoluteTopRootView, orientation)
        }
        
        if (showBlockLayout) {
          Ok(views.html.fragments.blockTree(treeDto, config.haplogroupType, treeViewModel, request.uri))
        } else {
          Ok(views.html.fragments.haplogroup(treeDto, config.haplogroupType, treeViewModel, request.uri, featureFlags.showBranchAgeEstimates))
        }
      }
      .recover {
        case _: IllegalArgumentException =>
          Ok(views.html.fragments.error(s"Haplogroup $haplogroupName not found"))
        case e =>
          Ok(views.html.fragments.error(e.getMessage))
      }
  }

  /**
   * Generates a tree structure for a given root haplogroup and renders it as either
   * a JSON response or an HTML fragment depending on the specified route type.
   *
   * This is where TreeLayoutService is now called for FragmentRoute responses.
   *
   * @param rootHaplogroup an optional string specifying the root haplogroup
   *                       for the tree. If None, the default root defined in
   *                       the configuration is used.
   * @param config         the tree configuration containing settings such
   *                       as the default root and haplogroup type.
   * @param routeType      the type of response to generate, either JSON
   *                       (for API responses) or HTML fragments.
   * @return an Action that produces either a JSON response with the tree
   *         structure or an HTML fragment based on the route type.
   */
  private def treeAction(
                          rootHaplogroup: Option[String],
                          config: TreeConfig,
                          routeType: services.RouteType
                        ): Action[AnyContent] = Action.async { implicit request =>

    val haplogroupName = rootHaplogroup.getOrElse(config.defaultRoot)
    val isAbsoluteTopRootView = haplogroupName == config.defaultRoot
    val showBlockLayout = shouldShowBlockLayout(request)
    val orientation = if (showBlockLayout) services.TreeOrientation.Vertical else services.TreeOrientation.Horizontal

    treeService.buildTreeResponse(haplogroupName, config.haplogroupType, routeType)
      .map { treeDto =>
        routeType match {
          case ApiRoute =>
            // TAPIR can't deal with the recursive tree, so we need to flatten it.
            val apiBody: Seq[SubcladeDTO] = treeService.mapApiResponse(treeDto.subclade)
            Ok(Json.toJson(apiBody))
          case FragmentRoute =>
            val treeViewModel: Option[TreeViewModel] = treeDto.subclade.flatMap { rootNodeDTO =>
              services.TreeLayoutService.layoutTree(treeDto, isAbsoluteTopRootView, orientation)
            }
            
            if (showBlockLayout) {
               Ok(views.html.fragments.blockTree(treeDto, config.haplogroupType, treeViewModel, request.uri))
            } else {
               Ok(views.html.fragments.haplogroup(treeDto, config.haplogroupType, treeViewModel, request.uri, featureFlags.showBranchAgeEstimates))
            }
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

  def getSnpDetailSidebar(haplogroupName: String, haplogroupType: HaplogroupType): Action[AnyContent] = Action.async { implicit request =>
    treeService.findHaplogroupWithVariants(haplogroupName, haplogroupType).map { case (haplogroup, snps) =>
      val provenance = haplogroup.flatMap(_.provenance)
      Ok(views.html.fragments.snpDetailSidebar(haplogroupName, snps, provenance))
    }
  }

  def emptySnpDetailSidebarPlaceholder: Action[AnyContent] = Action { implicit request =>
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