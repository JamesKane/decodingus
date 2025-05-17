package controllers

import jakarta.inject.Singleton
import org.webjars.play.WebJarsUtil
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}

import javax.inject.Inject

/**
 * Controller responsible for managing endpoints related to coverage information and benchmarks.
 *
 * @param controllerComponents Controller components used for handling HTTP-related functionality.
 * @param webJarsUtil          Utility for managing WebJars in Play framework.
 */
@Singleton
class CoverageController @Inject()(val controllerComponents: ControllerComponents)
                                  (using webJarsUtil: WebJarsUtil) extends BaseController  {

  /**
   * Handles an HTTP GET request to render the coverage benchmarks page.
   *
   * @return an Action that, when executed, returns an HTTP OK response containing the rendered HTML for the
   *         coverage benchmarks view.
   */
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.coverage())
  }

  /**
   * Handles an HTTP GET request to return benchmark information in JSON format.
   *
   * TODO: Implement this endpoint.
   * 
   * @return An HTTP OK response containing a JSON object with the key "status" and the value "ok".
   */
  def apiBenchmarks(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(Json.toJson(Map("status" -> "ok")))
  }
}
