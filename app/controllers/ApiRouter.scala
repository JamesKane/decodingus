package controllers

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

  // Swagger docs
  private val swaggerEndpoints =
    SwaggerInterpreter().fromEndpoints[Future](
      endpoints = _root_.api.ReferenceEndpoints.all ++ _root_.api.HaplogroupEndpoints.all
        ++ _root_.api.SampleEndpoints.all ++ _root_.api.CoverageEndpoints.all
        ++ _root_.api.SequencerEndpoints.all ++ _root_.api.FirehoseEndpoints.all
        ++ _root_.api.ProjectEndpoints.all,
      info = apiInfo
    )

  // Combine all endpoints ensuring Swagger endpoints come first
  private val serverEndpoints = swaggerEndpoints ::: Nil

  override def routes: Router.Routes = PlayServerInterpreter().toRoutes(serverEndpoints)
}
