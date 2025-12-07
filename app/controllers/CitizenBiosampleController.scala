package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.*

import java.util.UUID
import scala.concurrent.ExecutionContext

@Singleton
class CitizenBiosampleController @Inject()(
                                            val controllerComponents: ControllerComponents,
                                            secureApi: ApiSecurityAction,
                                            citizenBiosampleService: CitizenBiosampleService
                                          )(implicit ec: ExecutionContext) extends BaseController {

  def create: Action[ExternalBiosampleRequest] = secureApi.jsonAction[ExternalBiosampleRequest].async { request =>
    citizenBiosampleService.createBiosample(request.body).map { guid =>
      Created(Json.obj(
        "status" -> "success",
        "guid" -> guid
      ))
    }.recover {
      case e: DuplicateAccessionException =>
        Conflict(Json.obj("error" -> "Duplicate accession", "message" -> e.getMessage))
      case e: InvalidCoordinatesException =>
        BadRequest(Json.obj("error" -> "Invalid coordinates", "message" -> e.getMessage))
      case e: SequenceDataValidationException =>
        BadRequest(Json.obj("error" -> "Invalid sequence data", "message" -> e.getMessage))
      case e: PublicationLinkageException =>
        BadRequest(Json.obj("error" -> "Publication linkage failed", "message" -> e.getMessage))
      case e: BiosampleServiceException =>
        BadRequest(Json.obj("error" -> "Validation error", "message" -> e.getMessage))
      case e: IllegalArgumentException =>
        Conflict(Json.obj("error" -> "Conflict", "message" -> e.getMessage))
      case e: Exception =>
        InternalServerError(Json.obj("error" -> "Internal server error", "message" -> e.getMessage))
    }
  }

  def update(atUri: String): Action[ExternalBiosampleRequest] = secureApi.jsonAction[ExternalBiosampleRequest].async { request =>
    citizenBiosampleService.updateBiosample(atUri, request.body).map { guid =>
      Ok(Json.obj(
        "status" -> "success",
        "guid" -> guid
      ))
    }.recover {
      case e: IllegalStateException =>
        Conflict(Json.obj("error" -> "Optimistic locking failure", "message" -> e.getMessage))
      case e: NoSuchElementException =>
        NotFound(Json.obj("error" -> "Biosample not found", "message" -> e.getMessage))
      case e: InvalidCoordinatesException =>
        BadRequest(Json.obj("error" -> "Invalid coordinates", "message" -> e.getMessage))
      case e: Exception =>
        InternalServerError(Json.obj("error" -> "Internal server error", "message" -> e.getMessage))
    }
  }

  def delete(atUri: String): Action[AnyContent] = secureApi.async { request =>
    citizenBiosampleService.deleteBiosample(atUri).map {
      case true => NoContent
      case false => NotFound(Json.obj("error" -> "Biosample not found", "message" -> s"Biosample with atUri '$atUri' not found."))
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj(
          "error" -> "Internal server error",
          "message" -> s"An unexpected error occurred while attempting to delete biosample: ${e.getMessage}"
        ))
    }
  }
}