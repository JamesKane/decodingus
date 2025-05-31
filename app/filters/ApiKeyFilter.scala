package filters

import play.api.mvc._
import services.CachedSecretsManagerService
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject

class ApiKeyFilter @Inject()(
                              secretsManager: CachedSecretsManagerService
                            )(implicit ec: ExecutionContext) extends ActionFilter[Request] {

  private val ApiKeyHeader = "X-API-Key"

  def filter[A](request: Request[A]): Future[Option[Result]] = Future {
    request.headers.get(ApiKeyHeader) match {
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

  override protected def executionContext: ExecutionContext = ec
}