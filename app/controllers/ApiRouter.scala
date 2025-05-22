package controllers

import _root_.api.ReferenceEndpoints.*
import models.*
import models.api.PublicationWithEnaStudiesAndSampleCount
import org.apache.pekko.stream.Materializer
import play.api.mvc.*
import play.api.routing.{Router, SimpleRouter}
import sttp.apispec.openapi.Info
import sttp.tapir.*
import sttp.tapir.server.play.PlayServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiRouter @Inject()(cc: ControllerComponents, configuration: play.api.Configuration)
                         (implicit ec: ExecutionContext, mat: Materializer)
  extends SimpleRouter {

  // Create OpenAPI info object
  private val apiInfo = Info(
    title = "Decoding-Us API",
    version = "1.0.0",
    description = Some("API for accessing Decoding-Us data")
  )

  // Server logic implementation
  private def getReferenceDetails(unit: Unit): Future[Either[String, List[PublicationWithEnaStudiesAndSampleCount]]] =
    Future.successful(Right(List.empty[PublicationWithEnaStudiesAndSampleCount]))

  // Swagger docs
  private val swaggerEndpoints =
    SwaggerInterpreter().fromEndpoints[Future](
      endpoints = _root_.api.ReferenceEndpoints.all,
      info = apiInfo
    )

  // Combine all endpoints ensuring Swagger endpoints come first
  private val serverEndpoints =
    swaggerEndpoints ::: getReferenceDetailsEndpoint.serverLogic(getReferenceDetails) :: Nil

  override def routes: Router.Routes = PlayServerInterpreter().toRoutes(serverEndpoints)
}
