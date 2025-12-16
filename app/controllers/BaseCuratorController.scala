package controllers

import actions.{AuthenticatedAction, AuthenticatedRequest, PermissionAction}
import play.api.mvc.{ActionBuilder, AnyContent}

/**
 * Base trait for curator controllers providing common functionality.
 *
 * Extract common patterns like permission-based action composition and
 * curator ID extraction to reduce duplication across curator controllers.
 */
trait BaseCuratorController {

  /**
   * Inject these in the implementing controller.
   */
  protected def authenticatedAction: AuthenticatedAction
  protected def permissionAction: PermissionAction

  /**
   * Permission-based action composition.
   * Combines authentication with permission checking.
   *
   * Usage:
   * {{{
   * def myAction = withPermission("tree.version.view").async { implicit request =>
   *   // action implementation
   * }
   * }}}
   */
  protected def withPermission(permission: String): ActionBuilder[AuthenticatedRequest, AnyContent] =
    authenticatedAction andThen permissionAction(permission)

  /**
   * Extract curator ID from authenticated request.
   * Uses email if available, otherwise falls back to user ID.
   */
  protected def curatorId(request: AuthenticatedRequest[?]): String =
    request.user.email.getOrElse(request.user.id.map(_.toString).getOrElse("unknown"))
}
