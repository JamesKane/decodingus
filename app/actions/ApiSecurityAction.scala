package actions

import play.api.libs.json.Reads
import play.api.mvc.*

/**
 * A trait that defines an action builder for handling secure API requests,
 * including support for JSON payloads and custom authentication mechanisms.
 *
 * ApiSecurityAction extends ActionBuilder, providing a foundation for constructing actions
 * with additional security layers and JSON processing capabilities.
 */
trait ApiSecurityAction extends ActionBuilder[Request, AnyContent] {
  def jsonAction[A](implicit reader: Reads[A]): ActionBuilder[Request, A]
}