package actions

import filters.ApiKeyFilter
import play.api.libs.json.{JsError, Json}
import play.api.mvc.*
import play.api.mvc.Results.BadRequest

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
 * A custom action builder that secures API endpoints by enforcing an API key filter.
 * This action builder requires an API key to be provided as part of the request headers
 * to determine whether the request is authorized to proceed. It provides additional
 * functionality to validate JSON payloads when needed.
 *
 * @constructor Initializes the SecureApiAction with dependencies for API key filtering,
 *              request parsing, and controller components.
 * @param apiKeyFilter         An instance of ApiKeyFilter used to validate API keys in requests.
 * @param defaultParser        The default body parser for request parsing.
 * @param controllerComponents A set of helper methods to handle HTTP responses and extensions.
 * @param executionContext     An implicit ExecutionContext needed for asynchronously handling requests.
 */
class SecureApiAction @Inject()(
                                 apiKeyFilter: ApiKeyFilter,
                                 val defaultParser: BodyParsers.Default,
                                 val controllerComponents: ControllerComponents
                               )(implicit val executionContext: ExecutionContext) extends ActionBuilder[Request, AnyContent] {

  // Implement required parser member
  override def parser: BodyParser[AnyContent] = defaultParser

  /**
   * Authenticates an incoming request by filtering it using an API key mechanism and
   * determining whether to allow or block the request based on the provided API key.
   * If the API key is invalid or missing, a corresponding error response is returned.
   * Otherwise, the request is passed to the specified block for further processing.
   *
   * @param request the incoming HTTP request to be authenticated
   * @param block   a function representing the next processing step to be executed if authentication succeeds
   * @tparam A the type of the request body
   * @return a Future containing the result of the block if authentication succeeds,
   *         or an immediate error result if authentication fails
   */
  private def authenticate[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    apiKeyFilter.filter(request).flatMap {
      case Some(result) => Future.successful(result)
      case None => block(request)
    }
  }

  /**
   * Creates an `ActionBuilder` that parses incoming JSON requests and validates them
   * using the provided implicit `Reads` instance for the specified type `A`.
   * Ensures that the JSON request payload conforms to the expected structure,
   * returning a `BadRequest` with detailed validation errors if validation fails.
   * Delegates the authenticated request to the given processing block upon successful parsing.
   *
   * @param reader an implicit `Reads` instance that defines how to deserialize and validate
   *               the JSON request body into type `A`
   * @return an `ActionBuilder` that parses and validates the JSON body of incoming HTTP requests
   *         and passes the successfully parsed body to the provided block for further processing
   */
  def jsonAction[A](implicit reader: play.api.libs.json.Reads[A]): ActionBuilder[Request, A] = {
    new ActionBuilder[Request, A] {
      override def parser: BodyParser[A] = controllerComponents.parsers.json.validate(
        _.validate[A].asEither.left.map(e => BadRequest(Json.obj("message" -> JsError.toJson(e))))
      )

      override protected def executionContext: ExecutionContext = SecureApiAction.this.executionContext

      override def invokeBlock[B](request: Request[B], block: Request[B] => Future[Result]): Future[Result] = {
        authenticate(request, block)
      }
    }
  }

  /**
   * Overrides the `invokeBlock` method to add authentication logic to the incoming request
   * by leveraging the `authenticate` method. If authentication succeeds, the provided block
   * is invoked for further processing of the request.
   *
   * @param request the incoming HTTP request to be authenticated and processed
   * @param block   a function representing the next processing step, executed if authentication is successful
   * @tparam A the type of the request body
   * @return a Future containing the result of the block if authentication succeeds,
   *         or an error result if authentication fails
   */
  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    authenticate(request, block)
  }

}
