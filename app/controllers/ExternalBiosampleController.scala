package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.*

import scala.concurrent.ExecutionContext

case class ApiResponse(status: String)

object ApiResponse {
  implicit val format: OFormat[ApiResponse] = Json.format[ApiResponse]
}

/**
 * Controller for managing external biosample operations, such as creating biosamples.
 *
 * This controller handles HTTP actions related to external biosamples and interacts with the
 * `BiosampleDomainService` to perform operations such as creating a biosample with provided data.
 *
 * Key functionalities include securing endpoints via `SecureApiAction` and handling JSON payloads
 * representing external biosample requests.
 *
 * @param controllerComponents     The Play Framework `ControllerComponents` for handling requests and responses.
 * @param secureApi                The `SecureApiAction` responsible for securing access to this controller's endpoints.
 * @param biosampleDomainService   The facade service for all biosample operations.
 * @param ec                       An implicit `ExecutionContext` for handling asynchronous operations.
 */
@Singleton
class ExternalBiosampleController @Inject()(
                                             val controllerComponents: ControllerComponents,
                                             secureApi: ApiSecurityAction,
                                             biosampleDomainService: BiosampleDomainService
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
    biosampleDomainService.createExternalBiosample(request.body).map { guid =>
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

  /**
   * Handles an HTTP request to delete an external biosample by its accession.
   *
   * This method processes a request to delete a biosample identified by its unique accession.
   * The request must include the `citizenDid` to verify ownership and prevent collisions.
   * Upon successful deletion, it returns a `204 No Content` HTTP response.
   * If the biosample is not found or the DID does not match, it returns a `404 Not Found` response.
   *
   * @param accession  The unique accession of the biosample to be deleted.
   * @param citizenDid The DID of the citizen who owns the biosample.
   * @return An asynchronous `Action` that responds with `204 No Content`, `404 Not Found`,
   *         or `500 Internal Server Error` in case of an unexpected error.
   */
  def delete(accession: String, citizenDid: String): Action[AnyContent] = secureApi.async {
    biosampleDomainService.deleteBiosample(accession, citizenDid).map {
      case true => NoContent
      case false => NotFound(Json.obj("error" -> "Biosample not found", "message" -> s"Biosample with accession '$accession' and DID '$citizenDid' not found or mismatch."))
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj(
          "error" -> "Internal server error",
          "message" -> s"An unexpected error occurred while attempting to delete biosample with accession '$accession': ${e.getMessage}"
        ))
    }
  }
}