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


  /**
   * Adds sequence data associated with a sample identified by the given `sampleGuid`.
   *
   * This method processes a JSON payload of type `SequenceDataInfo`, sends the data to the external
   * biosample service for addition, and returns a success response upon completion.
   *
   * @param sampleGuid The unique identifier for the target sample to which the sequence data will be added.
   * @return An `Action` that expects a JSON body of type `SequenceDataInfo` and responds with a success message
   *         in JSON format upon successfully adding the sequence data.
   */
  def addSequenceData(sampleGuid: UUID): Action[SequenceDataInfo] =
    secureApi.jsonAction[SequenceDataInfo].async { request =>
      externalBiosampleService.addSequenceData(sampleGuid, request.body).map { _ =>
        Ok(Json.toJson(ApiResponse("success")))
      }
    }


  /**
   * Links a publication to a biosample identified by the given `sampleGuid`.
   *
   * This method processes a JSON payload of type `PublicationInfo`, invokes the `externalBiosampleService`
   * to associate the publication information with the specified sample, and returns a success response
   * upon successful linkage.
   *
   * @param sampleGuid The unique identifier for the biosample to which the publication will be linked.
   * @return An `Action` that expects a JSON body of type `PublicationInfo` and responds with a success message
   *         in JSON format upon successfully linking the publication.
   */
  def linkPublication(sampleGuid: UUID): Action[PublicationInfo] =
    secureApi.jsonAction[PublicationInfo].async { request =>
      externalBiosampleService.linkPublication(sampleGuid, request.body).map { _ =>
        Ok(Json.toJson(ApiResponse("success")))
      }
    }


}