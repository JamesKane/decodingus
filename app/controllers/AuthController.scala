package controllers

import jakarta.inject.{Inject, Singleton}
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.{AuthService, LoginRateLimiter}

import scala.concurrent.{ExecutionContext, Future}

case class LoginData(handle: String, appPassword: String)

@Singleton
class AuthController @Inject()(
                                val controllerComponents: ControllerComponents,
                                authService: AuthService,
                                userRoleRepository: repositories.UserRoleRepository,
                                rateLimiter: LoginRateLimiter
                              )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController with I18nSupport with Logging {

  val loginForm = Form(
    mapping(
      "handle" -> nonEmptyText(maxLength = 255),
      "appPassword" -> nonEmptyText(maxLength = 255)
    )(LoginData.apply)(data => Some((data.handle, data.appPassword)))
  )

  def login: Action[AnyContent] = Action { implicit request =>
    Ok(views.html.auth.login(loginForm))
  }

  def authenticate: Action[AnyContent] = Action.async { implicit request =>
    val clientIp = request.headers.get("X-Real-IP").getOrElse(request.remoteAddress)

    if (!rateLimiter.isAllowed(clientIp)) {
      Future.successful(
        Redirect(routes.AuthController.login)
          .flashing("error" -> "Too many login attempts. Please try again later.")
      )
    } else {
      loginForm.bindFromRequest().fold(
        formWithErrors => Future.successful(BadRequest(views.html.auth.login(formWithErrors))),
        data => {
          authService.login(data.handle, data.appPassword).flatMap {
            case Some(user) =>
              rateLimiter.recordSuccess(clientIp)
              // Fetch roles to store in session for UI logic
              userRoleRepository.getUserRoles(user.id.get).map { roles =>
                val roleString = roles.mkString(",")
                val displayName = user.displayName.orElse(user.handle).getOrElse("User")
                Redirect(routes.HomeController.index())
                  .withSession(
                    "userId" -> user.id.get.toString,
                    "userRoles" -> roleString,
                    "userDisplayName" -> displayName
                  )
                  .flashing("success" -> s"Welcome back, $displayName!")
              }
            case None =>
              rateLimiter.recordFailure(clientIp)
              Future.successful(
                Redirect(routes.AuthController.login)
                  .flashing("error" -> "Invalid handle or password.")
              )
          }
        }
      )
    }
  }

  def logout: Action[AnyContent] = Action { implicit request =>
    Redirect(routes.AuthController.login)
      .withNewSession
      .flashing("success" -> "You have been logged out.")
  }

  def showAppPasswordHelp(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.auth.appPasswordHelp())
  }
}
