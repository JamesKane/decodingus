package controllers

import actions.SecureApiAction
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

/**
 * Controller responsible for handling API operations related to external biosamples.
 *
 * This controller provides endpoints to create external biosamples, add sequence data,
 * and link publications to a specific biosample. It uses an injected service,
 * `ExternalBiosampleService`, to perform the underlying operations.
 *
 * Dependencies:
 * - ControllerComponents: Provides the necessary components for implementing the controller.
 * - ExternalBiosampleService: Handles the actual business logic related to biosample operations.
 * - ExecutionContext: Provides the execution context for asynchronous operations.
 */
@Singleton
class ExternalBiosampleController @Inject()(
                                             val controllerComponents: ControllerComponents,
                                             secureApi: SecureApiAction,
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
  def create: Action[ExternalBiosampleRequest] =     secureApi.jsonAction[ExternalBiosampleRequest].async { request =>
    externalBiosampleService.createBiosampleWithData(request.body).map { guid =>
      Created(Json.toJson(guid))
    }
  }
}