package controllers

import jakarta.inject.Inject
import models.api.{BiosampleUpdate, GeoCoord, BiosampleView}
import models.domain.genomics.Biosample
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, ControllerComponents}
import repositories.BiosampleRepository
import utils.GeometryUtils

import scala.concurrent.{ExecutionContext, Future}

class BiosampleController @Inject()(
                                     cc: ControllerComponents,
                                     biosampleRepository: BiosampleRepository
                                   )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def updateBiosample(id: Int): Action[JsValue] = Action(parse.json).async { request =>
    request.body.validate[BiosampleUpdate].fold(
      errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request format"))),
      update => {
        if (update.sex.isEmpty && update.geoCoord.isEmpty &&
          update.alias.isEmpty && update.locked.isEmpty) {
          Future.successful(BadRequest(Json.obj("error" -> "No valid fields to update")))
        } else {
          biosampleRepository.findById(id).flatMap {
            case None =>
              Future.successful(NotFound(Json.obj("error" -> "Biosample not found")))
            case Some(existingBiosample) if existingBiosample.locked && !update.locked.contains(false) =>
              Future.successful(Forbidden(Json.obj("error" -> "Biosample is locked")))
            case Some(existingBiosample) =>
              val updatedBiosample = existingBiosample.copy(
                sex = update.sex.orElse(existingBiosample.sex),
                geocoord = update.geoCoord.map(GeometryUtils.geoCoordToPoint)
                  .orElse(existingBiosample.geocoord),
                alias = update.alias.orElse(existingBiosample.alias),
                locked = update.locked.getOrElse(existingBiosample.locked)
              )

              biosampleRepository.update(updatedBiosample).map {
                case true => Ok(Json.toJson(BiosampleView.fromDomain(updatedBiosample)))
                case false => InternalServerError(Json.obj("error" -> "Failed to update biosample"))
              }
          }
        }
      }
    )
  }
}