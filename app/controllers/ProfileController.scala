package controllers

import actions.AuthenticatedAction
import jakarta.inject.{Inject, Singleton}
import models.domain.user.User
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.UserRepository
import org.webjars.play.WebJarsUtil

import scala.concurrent.{ExecutionContext, Future}

case class ProfileFormData(displayName: Option[String])

@Singleton
class ProfileController @Inject()(
                                   val controllerComponents: ControllerComponents,
                                   authenticatedAction: AuthenticatedAction,
                                   userRepository: UserRepository
                                 )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController with I18nSupport with Logging {

  val profileForm = Form(
    mapping(
      "displayName" -> optional(text(maxLength = 50))
    )(ProfileFormData.apply)(data => Some(data.displayName))
  )

  def view: Action[AnyContent] = authenticatedAction { implicit request =>
    val filledForm = profileForm.fill(ProfileFormData(request.user.displayName))
    Ok(views.html.user.profile(filledForm, request.user))
  }

  def update: Action[AnyContent] = authenticatedAction.async { implicit request =>
    profileForm.bindFromRequest().fold(
      formWithErrors => Future.successful(BadRequest(views.html.user.profile(formWithErrors, request.user))),
      data => {
        val updatedUser = request.user.copy(displayName = data.displayName)
        userRepository.update(updatedUser).map { _ =>
          Redirect(routes.ProfileController.view)
            .flashing("success" -> "Profile updated successfully.")
        }
      }
    )
  }
}
