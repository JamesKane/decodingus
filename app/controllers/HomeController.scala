package controllers

import org.webjars.play.WebJarsUtil

import javax.inject.*
import play.api.*
import play.api.mvc.{Action, *}

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents)
                              (using webJarsUtil: WebJarsUtil) extends BaseController {

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def cookieUsage(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.cookies())
  }

  def privacy(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.privacyPolicy())
  }

  def terms(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.terms())
  }
}
