package controllers

import org.webjars.play.WebJarsUtil
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}
import services.PublicationService

import javax.inject.*
import scala.concurrent.ExecutionContext

/**
 * Controller for managing and serving publications.
 *
 * This controller handles actions related to displaying and retrieving publications.
 * It supports both JSON and HTML responses, allowing integration with front-end views
 * and API responses.
 *
 * @constructor Creates a new instance of `PublicationController`.
 * @param controllerComponents The controller components for managing HTTP actions.
 * @param publicationService   The service layer responsible for publication-related operations.
 * @param webJarsUtil          A utility for WebJars integration, provided implicitly.
 * @param ec                   An execution context for handling asynchronous operations, provided implicitly.
 */
@Singleton
class PublicationController @Inject()(val controllerComponents: ControllerComponents, publicationService: PublicationService)
                                     (using webJarsUtil: WebJarsUtil, ec: ExecutionContext) extends BaseController with I18nSupport {
  /**
   * Renders the references page.
   *
   * This action handles GET requests to display the references page of the application. 
   * It serves an HTML view with static content and links related to references.
   *
   * @return an action that renders the References view as an HTML response
   */
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.references())
  }


  /**
   * Returns all publications along with their associated details in JSON format.
   *
   * This method handles an asynchronous request to fetch all publications and their
   * detailed information, including associated studies and sample counts. The response
   * is serialized as JSON and returned with an HTTP OK status.
   *
   * @return an asynchronous action that produces an HTTP response containing JSON-encoded 
   *         details of all publications.
   */
  def getAllPublicationsWithDetailsJson: Action[play.api.mvc.AnyContent] = Action.async {
    publicationService.getAllPublicationsWithDetails().map { publicationsWithDetails =>
      Ok(Json.toJson(publicationsWithDetails))
    }
  }

  /**
   * Fetches and displays a paginated list of publications along with their details in an HTML format.
   *
   * @param page     An optional parameter specifying the current page number for paginated results.
   *                 Defaults to the first page if not provided.
   * @param pageSize An optional parameter specifying the number of items per page for paginated results.
   *                 Defaults to 10 if not provided.
   * @return An asynchronous action that renders an HTML view containing the paginated publication details.
   */
  def getAllPublicationsWithDetailsHtml(page: Option[Int], pageSize: Option[Int]): Action[AnyContent] = Action.async { implicit request =>
    val currentPage = page.getOrElse(1)
    val currentPageSize = pageSize.getOrElse(10)
    publicationService.getPaginatedPublicationsWithDetails(currentPage, currentPageSize).map { paginatedResult =>
      Ok(views.html.publicationList(paginatedResult))
    }
  }
}