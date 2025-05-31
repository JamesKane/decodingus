package controllers

import jakarta.inject.Inject
import jakarta.inject.Singleton
import models.api.{ExternalBiosampleRequest, PublicationInfo, SequenceDataInfo}
import services.ExternalBiosampleService
import play.api.mvc.{Action, BaseController, ControllerComponents}
import play.api.libs.json.{Json, OFormat}

import java.util.UUID
import scala.concurrent.ExecutionContext

case class ApiResponse(status: String)

object ApiResponse {
  implicit val format: OFormat[ApiResponse] = Json.format[ApiResponse]
}

@Singleton
class ExternalBiosampleController @Inject()(
                                             val controllerComponents: ControllerComponents,
                                             externalBiosampleService: ExternalBiosampleService
                                           )(implicit ec: ExecutionContext) extends BaseController {

  def create: Action[ExternalBiosampleRequest] = Action.async(parse.json[ExternalBiosampleRequest]) { request =>
    externalBiosampleService.createBiosampleWithData(request.body).map { guid =>
      Created(Json.toJson(guid))
    }
  }

  def addSequenceData(sampleGuid: UUID): Action[SequenceDataInfo] = Action.async(parse.json[SequenceDataInfo]) { request =>
    externalBiosampleService.addSequenceData(sampleGuid, request.body).map { _ =>
      Ok(Json.toJson(ApiResponse("success")))
    }
  }

  def linkPublication(sampleGuid: UUID): Action[PublicationInfo] = Action.async(parse.json[PublicationInfo]) { request =>
    externalBiosampleService.linkPublication(sampleGuid, request.body).map { _ =>
      Ok(Json.toJson(ApiResponse("success")))
    }
  }

}