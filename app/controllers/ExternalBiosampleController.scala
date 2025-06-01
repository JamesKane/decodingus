package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.{BiosampleServiceException, DuplicateAccessionException, ExternalBiosampleService, InvalidCoordinatesException, PublicationLinkageException, SequenceDataValidationException}

import scala.concurrent.ExecutionContext

case class ApiResponse(status: String)

object ApiResponse {
  implicit val format: OFormat[ApiResponse] = Json.format[ApiResponse]
}

/**
 * Controller for managing external biosample operations, such as creating biosamples.
 *
 * This controller handles HTTP actions related to external biosamples and interacts with the
 * `ExternalBiosampleService` to perform operations such as creating a biosample with provided data.
 *
 * Key functionalities include securing endpoints via `SecureApiAction` and handling JSON payloads
 * representing external biosample requests.
 *
 * @param controllerComponents     The Play Framework `ControllerComponents` for handling requests and responses.
 * @param secureApi                The `SecureApiAction` responsible for securing access to this controller's endpoints.
 * @param externalBiosampleService The service layer used to perform operations related to external biosamples.
 * @param ec                       An implicit `ExecutionContext` for handling asynchronous operations.
 */
@Singleton
class ExternalBiosampleController @Inject()(
                                             val controllerComponents: ControllerComponents,
                                             secureApi: ApiSecurityAction,
                                             externalBiosampleService: ExternalBiosampleService
                                           )(implicit ec: ExecutionContext) extends BaseController {

  /**
   * Handles an HTTP request to create an external biosample.
   *
   * This method processes a JSON payload of type `ExternalBiosampleRequest` and invokes the `externalBiosampleService`
   * to create a new biosample with the provided data. Upon successful creation, it returns a `201 Created` HTTP response
   * containing the GUID of the newly created biosample in JSON format.
   *
   * @return An asynchronous `Action` that expects a JSON request body of type `ExternalBiosampleRequest`
   *         and responds with the GUID of the created biosample in JSON format.
   */
  def create: Action[ExternalBiosampleRequest] = secureApi.jsonAction[ExternalBiosampleRequest].async { request =>
    externalBiosampleService.createBiosampleWithData(request.body).map { guid =>
      Created(Json.obj(
        "status" -> "success",
        "guid" -> guid
      ))
    }.recover {
      case e: DuplicateAccessionException =>
        Conflict(Json.obj(
          "error" -> "Duplicate accession",
          "message" -> e.getMessage
        ))

      case e: InvalidCoordinatesException =>
        BadRequest(Json.obj(
          "error" -> "Invalid coordinates",
          "message" -> e.getMessage
        ))

      case e: SequenceDataValidationException =>
        BadRequest(Json.obj(
          "error" -> "Invalid sequence data",
          "message" -> e.getMessage
        ))

      case e: PublicationLinkageException =>
        BadRequest(Json.obj(
          "error" -> "Publication linkage failed",
          "message" -> e.getMessage
        ))

      case e: BiosampleServiceException =>
        BadRequest(Json.obj(
          "error" -> "Validation error",
          "message" -> e.getMessage
        ))

      case e: Exception =>
        InternalServerError(Json.obj(
          "error" -> "Internal server error",
          "message" -> "An unexpected error occurred while processing the request"
        ))
    }
  }

}