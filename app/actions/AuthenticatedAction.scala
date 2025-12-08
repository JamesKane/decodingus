package actions

import models.domain.user.User
import play.api.mvc._
import services.AuthService

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

// Custom Request type that holds the authenticated User
class AuthenticatedRequest[A](val user: User, request: Request[A]) extends WrappedRequest[A](request)

/**
 * AuthenticatedAction builder.
 * Checks for a session "userId" and fetches the user.
 */
class AuthenticatedAction @Inject()(
                                     val parser: BodyParsers.Default,
                                     val authService: AuthService,
                                     val userRepository: repositories.UserRepository
                                   )(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[AuthenticatedRequest, AnyContent]
    with ActionRefiner[Request, AuthenticatedRequest] {

  override protected def refine[A](request: Request[A]): Future[Either[Result, AuthenticatedRequest[A]]] = {
    request.session.get("userId") match {
      case Some(userIdStr) =>
        try {
          val userId = UUID.fromString(userIdStr)
          userRepository.findById(userId).map {
            case Some(user) if user.isActive => Right(new AuthenticatedRequest(user, request))
            case _ => Left(Results.Redirect(controllers.routes.AuthController.login).withNewSession)
          }
        } catch {
          case _: IllegalArgumentException =>
            Future.successful(Left(Results.Redirect(controllers.routes.AuthController.login).withNewSession))
        }
      case None =>
        Future.successful(Left(Results.Redirect(controllers.routes.AuthController.login)))
    }
  }
}

/**
 * RoleAction builder factory.
 * Allows requiring specific roles on top of authentication.
 */
class RoleAction @Inject()(authService: AuthService)(implicit ec: ExecutionContext) {
  
  def apply(requiredRoles: String*): ActionFilter[AuthenticatedRequest] = new ActionFilter[AuthenticatedRequest] {
    override protected def executionContext: ExecutionContext = ec

    override protected def filter[A](request: AuthenticatedRequest[A]): Future[Option[Result]] = {
      authService.hasAnyRole(request.user.id.get, requiredRoles).map { hasRole =>
        if (hasRole) None
        else Some(Results.Forbidden("You do not have the required permissions to access this resource."))
      }
    }
  }
}
