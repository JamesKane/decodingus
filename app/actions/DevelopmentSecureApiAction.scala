package actions

import org.apache.pekko.stream.Materializer
import play.api.libs.json.{JsError, Json, Reads}
import play.api.mvc.Results.BadRequest
import play.api.mvc._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/**
 * A development-mode implementation of the `ApiSecurityAction` trait, providing an unsecured
 * means to handle API requests that supports JSON payload parsing and processing.
 *
 * This class is designed for use in development environments where full security measures are not
 * required. It bypasses authentication mechanisms and directly allows the handling of requests.
 *
 * @param controllerComponents Controller components for configuration and auxiliary services
 * @param defaultParser        Default body parser for handling request payloads
 * @param executionContext     The implicit `ExecutionContext` for asynchronous operations
 * @param materializer         The stream `Materializer` for managing Play's asynchronous streams
 */
@Singleton
class DevelopmentSecureApiAction @Inject()(
                                            val controllerComponents: ControllerComponents,
                                            val defaultParser: BodyParsers.Default
                                          )(implicit val executionContext: ExecutionContext, materializer: Materializer) extends ApiSecurityAction {

  override def parser: BodyParser[AnyContent] = defaultParser

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
  override def jsonAction[A](implicit reader: Reads[A]): ActionBuilder[Request, A] = {
    new ActionBuilder[Request, A] {
      override def parser: BodyParser[A] = controllerComponents.parsers.json.validate(
        _.validate[A].asEither.left.map(e => BadRequest(Json.obj("message" -> JsError.toJson(e))))
      )

      override protected def executionContext: ExecutionContext = DevelopmentSecureApiAction.this.executionContext

      override def invokeBlock[B](request: Request[B], block: Request[B] => Future[Result]): Future[Result] = {
        block(request)
      }
    }
  }

  /**
   * Overrides the `invokeBlock` method to execute a block of request handling logic.
   * This implementation directly invokes the provided block without any additional
   * preprocessing or filtering, making it suitable for development purposes.
   *
   * @param request the incoming HTTP request of type `A`
   * @param block   a function that processes the HTTP request and returns a `Future[Result]`
   * @return a `Future[Result]` produced by executing the provided block
   */
  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    block(request)
  }
}