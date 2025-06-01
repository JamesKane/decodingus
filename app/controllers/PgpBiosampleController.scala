package controllers

import actions.SecureApiAction
import jakarta.inject.{Inject, Singleton}
import models.api.PgpBiosampleRequest
import play.api.libs.json.Json
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.PgpBiosampleService

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
                                        secureApi: SecureApiAction,
                                        pgpBiosampleService: PgpBiosampleService
                                      )(implicit ec: ExecutionContext) extends BaseController {

  /**
   * Handles the creation of a PGP biosample by processing an incoming HTTP request, extracting
   * and validating the data, and delegating the actual creation operation to the service layer.
   *
   * The method expects a JSON payload that conforms to the structure of `PgpBiosampleRequest`,
   * containing the participant identifier, the sample's decentralized identifier (DID), a description,
   * the name of the center that provided the sample, and optionally, the sex of the participant.
   *
   * @return An `Action` that asynchronously creates a new biosample and responds with the unique identifier (UUID)
   *         of the created sample upon success.
   */
  def create: Action[PgpBiosampleRequest] = secureApi.jsonAction[PgpBiosampleRequest].async { request =>
    pgpBiosampleService.createPgpBiosample(
      participantId = request.body.participantId,
      sampleDid = request.body.sampleDid,
      description = request.body.description,
      centerName = request.body.centerName,
      sex = request.body.sex
    ).map { guid =>
      Created(Json.toJson(guid))
    }
  }
}