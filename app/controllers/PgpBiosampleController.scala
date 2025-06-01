package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.PgpBiosampleRequest
import play.api.libs.json.Json
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.{DuplicateParticipantException, InvalidCoordinatesException, PgpBiosampleService}

import scala.concurrent.ExecutionContext

/**
 * Controller responsible for handling PGP (Personal Genome Project) biosample-related actions.
 *
 * This controller provides endpoints for managing and creating biosample entities that
 * represent biological samples associated with participants in the PGP. The main responsibility
 * is to facilitate the creation of biosamples by processing incoming HTTP requests, validating
 * the data, and delegating the actual sample creation logic to the service layer.
 *
 * @constructor Initializes the controller with its required dependencies.
 * @param controllerComponents A base set of helper methods provided by Play Framework for handling HTTP responses.
 * @param secureApi            An action builder that secures API endpoints by enforcing strict authentication and JSON validation.
 * @param pgpBiosampleService  The service layer responsible for handling the business logic of PGP biosample creation and management.
 * @param ec                   An implicit execution context for asynchronous operations.
 */
@Singleton
class PgpBiosampleController @Inject()(
                                        val controllerComponents: ControllerComponents,
                                        secureApi: ApiSecurityAction,
                                        pgpBiosampleService: PgpBiosampleService
                                      )(implicit ec: ExecutionContext) extends BaseController {

  /**
   * Handles the creation of a new PGP biosample. Validates the input request, creates the biosample in the system,
   * and returns a response with the unique identifier of the created biosample. If an error occurs during processing,
   * appropriate HTTP responses are returned based on the error type.
   *
   * @return A Play `Action` for processing a request with a `PgpBiosampleRequest` body. Returns the following:
   *         - `201 Created` with the unique identifier of the created biosample on success.
   *         - `409 Conflict` if a biosample for the specified participant already exists.
   *         - `400 Bad Request` if the input request contains invalid data (e.g., invalid coordinates).
   *         - `500 Internal Server Error` if an unexpected issue occurs during processing.
   */
  def create: Action[PgpBiosampleRequest] = secureApi.jsonAction[PgpBiosampleRequest].async { request =>
    pgpBiosampleService.createPgpBiosample(
      participantId = request.body.participantId,
      description = request.body.description,
      centerName = request.body.centerName,
      sex = request.body.sex,
      latitude = request.body.latitude,
      longitude = request.body.longitude
    ).map { guid =>
      Created(Json.toJson(guid))
    }.recover {
      case e: DuplicateParticipantException =>
        Conflict(Json.obj(
          "error" -> "Duplicate submission",
          "message" -> s"A biosample for participant ${request.body.participantId} already exists",
          "details" -> e.getMessage
        ))
      case e: InvalidCoordinatesException =>
        BadRequest(Json.obj(
          "error" -> "Invalid coordinates",
          "message" -> e.getMessage
        ))
      case e: IllegalArgumentException =>
        BadRequest(Json.obj(
          "error" -> "Invalid request",
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