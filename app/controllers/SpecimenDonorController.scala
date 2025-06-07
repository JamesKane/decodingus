package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.SpecimenDonorMergeRequest
import play.api.libs.json.Json
import play.api.mvc.*
import services.genomics.SpecimenDonorService

import scala.concurrent.ExecutionContext

@Singleton
class SpecimenDonorController @Inject()(
                                         donorService: SpecimenDonorService,
                                         secureApi: ApiSecurityAction,
                                         cc: ControllerComponents
                                       )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def mergeDonors(): Action[SpecimenDonorMergeRequest] = Action.async(parse.json[SpecimenDonorMergeRequest]) { request =>
    secureApi.invokeBlock(request, { secureRequest =>
      donorService.mergeDonors(request.body).map { result =>
        Ok(Json.toJson(result))
      }.recover {
        case e: IllegalArgumentException => BadRequest(Json.obj("error" -> e.getMessage))
        case e: Exception => InternalServerError(Json.obj("error" -> "Failed to merge donors"))
      }
    })
  }
}
