package filters

import play.api.mvc.*
import services.CachedSecretsManagerService

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
 * A filter that validates API key headers in incoming requests.
 *
 * This class checks for the presence of an API key in the request headers and validates
 * it against a stored API key retrieved from a cached secrets manager service. If a valid
 * API key is not provided, the request is rejected with a `401 Unauthorized` response.
 *
 * @constructor Creates a new instance of the filter with the given secrets manager service.
 * @param secretsManager The service used to retrieve and cache the API key.
 * @param ec             The implicit ExecutionContext for handling asynchronous operations.
 */
class ApiKeyFilter @Inject()(
                              secretsManager: CachedSecretsManagerService
                            )(implicit ec: ExecutionContext){

  private val ApiKeyHeader = "X-API-Key"

  /**
   * Filters an incoming HTTP request based on the presence and validity of an API key in the request headers.
   *
   * @param requestHeader the HTTP request headers provided for the incoming request
   * @return a Future containing an Option of Result, where:
   *         - None indicates that the request is authorized and can proceed
   *         - Some(Result) indicates that the request is unauthorized and includes the appropriate response
   */
  def filter(requestHeader: RequestHeader): Future[Option[Result]] = Future {
    requestHeader.headers.get(ApiKeyHeader) match {
      case None =>
        Some(Results.Unauthorized("API key missing"))
      case Some(providedKey) =>
        secretsManager.getCachedApiKey match {
          case Some(storedKey) if providedKey == storedKey =>
            None // Allow the request to proceed
          case _ =>
            Some(Results.Unauthorized("Invalid API key"))
        }
    }
  }
}