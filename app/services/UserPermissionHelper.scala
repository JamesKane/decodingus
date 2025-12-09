package services

import jakarta.inject.{Inject, Singleton}
import models.domain.user.User
import play.api.mvc.RequestHeader
import services.AuthService

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserPermissionHelper @Inject()(
                                      authService: AuthService
                                    )(implicit ec: ExecutionContext) {

  /**
   * Checks if the current user in the request has the specified permission.
   *
   * @param permissionName The name of the permission to check.
   * @param request        The current request header (containing the session).
   * @return Future[Boolean] True if the user has the permission, false otherwise.
   */
  def hasPermission(permissionName: String)(implicit request: RequestHeader): Future[Boolean] = {
    request.session.get("userId") match {
      case Some(userIdStr) =>
        try {
          val userId = UUID.fromString(userIdStr)
          authService.hasPermission(userId, permissionName)
        } catch {
          case _: IllegalArgumentException => Future.successful(false)
        }
      case None => Future.successful(false)
    }
  }
}
