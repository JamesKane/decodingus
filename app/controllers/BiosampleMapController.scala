package controllers

import play.api.mvc.*
import play.api.libs.json.*
import models.dal.domain.genomics.BiosamplesTable
import org.webjars.play.WebJarsUtil
import repositories.BiosampleRepository

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BiosampleMapController @Inject()(
                                        val controllerComponents: ControllerComponents,
                                        biosampleRepository: BiosampleRepository
                                      )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController {

  def mapView() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.biosamples.map())
  }

  def geoData() = Action.async { implicit request =>
    biosampleRepository.getAllGeoLocations().map { locations =>
      val geoJson = locations.map { case (point, count) =>
        Json.obj(
          "lat" -> point.getY,
          "lng" -> point.getX,
          "count" -> count
        )
      }
      Ok(Json.toJson(geoJson))
    }
  }
}