package controllers

import actions.SecureApiAction
import jakarta.inject.{Inject, Singleton}
import models.api.{PublicationInfo, SequenceDataInfo}
import play.api.libs.json.Json
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.BiosampleDataService

import java.util.UUID
import scala.concurrent.ExecutionContext

@Singleton
class BiosampleDataController @Inject()(
                                         val controllerComponents: ControllerComponents,
                                         secureApi: SecureApiAction,
                                         biosampleDataService: BiosampleDataService
                                       )(implicit ec: ExecutionContext) extends BaseController {

  def addSequenceData(sampleGuid: UUID): Action[SequenceDataInfo] =
    secureApi.jsonAction[SequenceDataInfo].async { request =>
      biosampleDataService.addSequenceData(sampleGuid, request.body).map { _ =>
        Ok(Json.toJson(ApiResponse("success")))
      }
    }

  def linkPublication(sampleGuid: UUID): Action[PublicationInfo] =
    secureApi.jsonAction[PublicationInfo].async { request =>
      biosampleDataService.linkPublication(sampleGuid, request.body).map { _ =>
        Ok(Json.toJson(ApiResponse("success")))
      }
    }
}