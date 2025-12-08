package actions

import org.apache.pekko.stream.Materializer
import play.api.libs.json.{JsError, Json, Reads}
import play.api.mvc.Results.BadRequest
import play.api.mvc.*

import scala.concurrent.ExecutionContext

/**
 * A trait providing common JSON validation and parsing capabilities for ActionBuilders.
 * This helps to reduce boilerplate and ensure consistent JSON error responses across different API actions.
 */
trait JsonValidation {
  def controllerComponents: ControllerComponents
  implicit def executionContext: ExecutionContext
  implicit def materializer: Materializer

  /**
   * Constructs an `ActionBuilder` for handling requests with JSON payloads, allowing the payload to be
   * validated and parsed into a specified type `A`.
   *
   * This method sets up the necessary JSON body parser and validation mechanism using the provided
   * implicit `Reads[A]`. If the JSON validation fails, an appropriate error response is returned.
   *
   * @param reader an implicit JSON `Reads[A]` type class, used to validate and parse the JSON body into type `A`
   * @return an `ActionBuilder` configured to process requests with JSON payloads, validating and parsing them into type `A`
   */
  protected def jsonBodyParser[A](implicit reader: Reads[A]): BodyParser[A] =
    controllerComponents.parsers.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(Json.obj("message" -> JsError.toJson(e))))
    )
}
