package controllers

import jakarta.inject.{Inject, Singleton}
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.AuthService

import scala.concurrent.{ExecutionContext, Future}

case class LoginData(handle: String, appPassword: String)

@Singleton
class AuthController @Inject()(
                                val controllerComponents: ControllerComponents,
                                authService: AuthService
                              )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController with I18nSupport with Logging {

  val loginForm = Form(
    mapping(
      "handle" -> nonEmptyText,
      "appPassword" -> nonEmptyText
    )(LoginData.apply)(data => Some((data.handle, data.appPassword)))
  )

  def login: Action[AnyContent] = Action { implicit request =>
    Ok(views.html.auth.login(loginForm))
  }

  def authenticate: Action[AnyContent] = Action.async { implicit request =>
    loginForm.bindFromRequest().fold(
      formWithErrors => Future.successful(BadRequest(views.html.auth.login(formWithErrors))),
      data => {
        authService.login(data.handle, data.appPassword).map {
          case Some(user) =>
            Redirect(routes.HomeController.index())
              .withSession("userId" -> user.id.get.toString)
              .flashing("success" -> s"Welcome back, ${user.handle.getOrElse("User")}!")
          case None =>
            Redirect(routes.AuthController.login)
              .flashing("error" -> "Invalid handle or password.")
        }
      }
    )
  }

  def logout: Action[AnyContent] = Action { implicit request =>
    Redirect(routes.AuthController.login)
      .withNewSession
      .flashing("success" -> "You have been logged out.")
  }
}
