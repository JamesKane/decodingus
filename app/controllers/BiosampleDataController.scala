package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.{PublicationInfo, SequenceDataInfo}
import play.api.libs.json.Json
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.BiosampleDomainService

import java.util.UUID
import scala.concurrent.ExecutionContext

/**
 * A controller that manages operations related to biosample data. This includes
 * functionalities for adding sequence data and linking publications to specific
 * biosamples. All API endpoints are secured using the SecureApiAction.
 *
 * @constructor Creates an instance of BiosampleDataController.
 * @param controllerComponents Standard Play framework controller components.
 * @param secureApi            A custom SecureApiAction used to enforce authentication and JSON validation.
 * @param biosampleDomainService The facade service for all biosample operations.
 * @param ec                   An implicit ExecutionContext for asynchronous operations.
 */
@Singleton
class BiosampleDataController @Inject()(
                                         val controllerComponents: ControllerComponents,
                                         secureApi: ApiSecurityAction,
                                         biosampleDomainService: BiosampleDomainService
                                       )(implicit ec: ExecutionContext) extends BaseController {

  /**
   * Adds sequencing data to a specified biosample.
   *
   * This method handles an HTTP request to associate sequencing dataset metadata
   * with a particular biosample identified by the given sample GUID.
   * The request body contains `SequenceDataInfo`, which includes detailed sequencing-related information
   * such as platform name, read length, and test type. Authentication and JSON validation are enforced
   * using the `SecureApiAction`.
   *
   * @param sampleGuid The unique identifier (UUID) of the biosample to which the sequence data will be added.
   * @return An `Action` that asynchronously performs the operation and produces
   *         a `SequenceDataInfo`, returning an HTTP response indicating success or failure.
   */
  def addSequenceData(sampleGuid: UUID): Action[SequenceDataInfo] =
    secureApi.jsonAction[SequenceDataInfo].async { request =>
      biosampleDomainService.addSequenceData(sampleGuid, request.body).map { _ =>
        Ok(Json.toJson(ApiResponse("success")))
      }
    }

  /**
   * Links a publication to a specific biosample identified by the given GUID.
   *
   * This method processes a request containing `PublicationInfo` to associate it
   * with the biosample corresponding to the provided GUID. The operation is performed
   * asynchronously, and upon completion, a success response is returned.
   *
   * @param sampleGuid The unique identifier (UUID) of the biosample to which the publication will be linked.
   * @return An `Action` that performs the linking operation and produces a result containing `PublicationInfo`.
   */
  def linkPublication(sampleGuid: UUID): Action[PublicationInfo] =
    secureApi.jsonAction[PublicationInfo].async { request =>
      biosampleDomainService.linkPublication(sampleGuid, request.body).map { _ =>
        Ok(Json.toJson(ApiResponse("success")))
      }
    }
}