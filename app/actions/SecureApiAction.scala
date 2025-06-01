package actions

import filters.ApiKeyFilter
import org.apache.pekko.stream.Materializer
import play.api.libs.json.{JsError, Json}
import play.api.libs.streams.Accumulator
import play.api.mvc.*
import play.api.mvc.Results.BadRequest

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
 * A custom implementation of ActionBuilder that securely handles API requests
 * by enforcing an API key validation mechanism and providing specialized support
 * for JSON payloads.
 *
 * @param apiKeyFilter         Instance of ApiKeyFilter used to validate API keys in incoming requests
 * @param defaultParser        Default body parser for handling request payloads
 * @param controllerComponents Controller components for configuration and auxiliary services
 * @param executionContext     The implicit ExecutionContext for asynchronous operations
 * @param materializer         The stream Materializer for managing Play's asynchronous streams
 */
class SecureApiAction @Inject()(
                                 apiKeyFilter: ApiKeyFilter,
                                 val defaultParser: BodyParsers.Default,
                                 val controllerComponents: ControllerComponents
                               )(implicit val executionContext: ExecutionContext, materializer: Materializer) extends ActionBuilder[Request, AnyContent] {

  override def parser: BodyParser[AnyContent] = defaultParser

  /**
   * Constructs an `ActionBuilder` for handling JSON requests and parsing their bodies into a specified type `A`,
   * with support for API key filtering and JSON validation.
   *
   * This method ensures that incoming requests are authenticated using an API key filter.
   * It also validates the request body as JSON and attempts to parse it as type `A` using the implicit `Reads[A]`.
   * If the API key is missing/invalid or the JSON validation fails, appropriate error responses are generated.
   *
   * @param reader an implicit JSON `Reads[A]` type class, used to validate and parse the JSON body into type `A`
   * @return a configured `ActionBuilder` that processes JSON requests as type `A` and applies API key authentication
   */
  def jsonAction[A](implicit reader: play.api.libs.json.Reads[A]): ActionBuilder[Request, A] = {
    new ActionBuilder[Request, A] {
      override def parser: BodyParser[A] = BodyParser { requestHeader =>
        val jsonParser = controllerComponents.parsers.json.validate(
          _.validate[A].asEither.left.map(e => BadRequest(Json.obj("message" -> JsError.toJson(e))))
        )

        val accumulator = apiKeyFilter.filter(requestHeader).map {
          case Some(result) => Accumulator.done(Left(result))
          case None => jsonParser(requestHeader)
        }

        Accumulator.flatten(accumulator)
      }

      override protected def executionContext: ExecutionContext = SecureApiAction.this.executionContext

      override def invokeBlock[B](request: Request[B], block: Request[B] => Future[Result]): Future[Result] = {
        block(request)
      }
    }
  }

  /**
   * Invokes the provided block of request handling logic after applying the API key filter to the incoming request.
   * If the request passes authentication, the block is executed. Otherwise, an appropriate error response is returned.
   *
   * @param request the incoming HTTP request of type `A`
   * @param block   a function that processes the authenticated HTTP request and returns a `Future[Result]`
   * @return a `Future[Result]` representing the HTTP response, either from the API key filter or the provided block
   */
  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
    apiKeyFilter.filter(request).flatMap {
      case Some(result) => Future.successful(result)
      case None => block(request)
    }
  }
}