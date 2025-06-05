package controllers

import actions.ApiSecurityAction
import jakarta.inject.Inject
import models.api.{BiosampleUpdate, BiosampleView, GeoCoord}
import models.domain.genomics.{Biosample, BiosampleType}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.BiosampleRepository
import utils.GeometryUtils

import scala.concurrent.{ExecutionContext, Future}

class BiosampleController @Inject()(
                                     cc: ControllerComponents,
                                     secureApi: ApiSecurityAction,
                                     biosampleRepository: BiosampleRepository
                                   )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def updateBiosample(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    secureApi.invokeBlock(request, { secureRequest =>
      request.body.validate[BiosampleUpdate].fold(
        errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request format"))),
        update => {
          if (update.sex.isEmpty && update.geoCoord.isEmpty &&
            update.alias.isEmpty && update.locked.isEmpty &&
            update.dateRangeStart.isEmpty && update.dateRangeEnd.isEmpty) {
            Future.successful(BadRequest(Json.obj("error" -> "No valid fields to update")))
          } else {
            biosampleRepository.findById(id).flatMap {
              case None =>
                Future.successful(NotFound(Json.obj("error" -> "Biosample not found")))
              case Some(existingBiosample) =>
                // Update the type to Ancient if dates are being set
                val newType = if (update.dateRangeStart.isDefined || update.dateRangeEnd.isDefined) {
                  BiosampleType.Ancient
                } else {
                  existingBiosample.sampleType
                }

                val updatedBiosample = existingBiosample.copy(
                  sex = update.sex.orElse(existingBiosample.sex),
                  geocoord = update.geoCoord.map(GeometryUtils.geoCoordToPoint)
                    .orElse(existingBiosample.geocoord),
                  alias = update.alias.orElse(existingBiosample.alias),
                  locked = update.locked.getOrElse(existingBiosample.locked),
                  dateRangeStart = update.dateRangeStart.orElse(existingBiosample.dateRangeStart),
                  dateRangeEnd = update.dateRangeEnd.orElse(existingBiosample.dateRangeEnd),
                  sampleType = newType
                )

                biosampleRepository.update(updatedBiosample).map {
                  case true => Ok(Json.toJson(BiosampleView.fromDomain(updatedBiosample)))
                  case false => InternalServerError(Json.obj("error" -> "Failed to update biosample"))
                }
            }
          }
        }
      )
    })
  }


  def getSamplesWithStudies: Action[AnyContent] = Action.async {
    biosampleRepository.findAllWithStudies().map { samples =>
      Ok(Json.toJson(samples))
    }
  }

  def findByAliasOrAccession(query: String): Action[AnyContent] = Action.async {
    biosampleRepository.findByAliasOrAccession(query).map {
      case Some(biosample) => Ok(Json.toJson(BiosampleView.fromDomain(biosample)))
      case None => NotFound(Json.obj("error" -> "Biosample not found"))
    }
  }
}