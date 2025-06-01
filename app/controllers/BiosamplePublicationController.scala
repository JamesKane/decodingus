package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.BiosamplePublicationLinkRequest
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, ControllerComponents}
import services.BiosamplePublicationService

import scala.concurrent.ExecutionContext

/**
 * Controller for managing the linkage between biosamples and publications.
 *
 * This controller provides an endpoint for associating a biosample with a publication
 * based on their respective identifiers. The operation ensures proper validation of
 * input data and handles potential errors during the linking process.
 *
 * @constructor Creates a new instance of BiosamplePublicationController.
 * @param cc                          Controller components used for handling requests and responses.
 * @param secureApi                   Security action for validating and processing secure API requests.
 * @param biosamplePublicationService Service for managing biosample-publication associations.
 * @param ec                          Execution context for asynchronous operations.
 */
@Singleton
class BiosamplePublicationController @Inject()(
                                                cc: ControllerComponents,
                                                secureApi: ApiSecurityAction,
                                                biosamplePublicationService: BiosamplePublicationService
                                              )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /**
   * Links a biosample to a publication by processing a request containing their identifiers.
   *
   * This method accepts a JSON request body of type `BiosamplePublicationLinkRequest`.
   * It triggers the `biosamplePublicationService` to establish a link between the specified
   * biosample and publication. If successfully linked, a `201 Created` response is returned
   * with the details of the link. In case of errors, appropriate HTTP error responses are returned:
   * - `BadRequest` for invalid input or missing resources.
   * - `InternalServerError` for unexpected server errors or data integrity issues.
   *
   * @return An asynchronous Play Framework Action that processes the request, performs the linking
   *         operation, and produces a JSON-based HTTP response with success or error details.
   */
  def linkBiosampleToPublication: Action[BiosamplePublicationLinkRequest] =
    secureApi.jsonAction[BiosamplePublicationLinkRequest].async { request =>
      biosamplePublicationService.linkBiosampleToPublication(
        request.body.sampleAccession,
        request.body.publicationDoi
      ).map { link =>
        Created(Json.obj(
          "message" -> "Biosample successfully linked to publication",
          "publicationId" -> link.publicationId,
          "biosampleId" -> link.biosampleId
        ))
      }.recover {
        case e: IllegalArgumentException =>
          BadRequest(Json.obj(
            "error" -> "Invalid request",
            "message" -> e.getMessage
          ))
        case e: IllegalStateException =>
          InternalServerError(Json.obj(
            "error" -> "Data integrity error",
            "message" -> e.getMessage
          ))
        case e: Exception =>
          InternalServerError(Json.obj(
            "error" -> "Internal server error",
            "message" -> "Failed to link biosample to publication"
          ))
      }
    }
}