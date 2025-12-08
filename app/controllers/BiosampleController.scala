package controllers

import actions.ApiSecurityAction
import jakarta.inject.Inject
import models.api.{BiosampleUpdate, BiosampleView}
import models.domain.genomics.Biosample
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.BiosampleRepository
import services.BiosampleUpdateService

import scala.concurrent.{ExecutionContext, Future}

/**
 * The BiosampleController class provides HTTP endpoints for managing biosample-related
 * operations, including updating biosamples, retrieving biosamples with studies,
 * and searching biosamples by alias or accession.
 *
 * @constructor Creates a new instance of the BiosampleController class.
 * @param cc                     the controller components used for handling HTTP requests and responses
 * @param secureApi              an action builder for processing secure API requests
 * @param biosampleRepository    a repository interface for accessing biosample data
 * @param biosampleUpdateService a service for handling biosample update operations
 * @param ec                     the implicit execution context for handling asynchronous operations
 */
class BiosampleController @Inject()(
                                     cc: ControllerComponents,
                                     secureApi: ApiSecurityAction,
                                     biosampleRepository: BiosampleRepository,
                                     biosampleUpdateService: BiosampleUpdateService
                                   )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /**
   * Updates the details of a biosample based on the provided identifier and update information.
   *
   * @param id the identifier of the biosample to be updated
   * @return an asynchronous action producing a JSON response:
   *         - On success, returns the updated biosample in the response.
   *         - On failure, returns an error message indicating the issue.
   */
  def updateBiosample(id: Int): Action[JsValue] = Action.async(parse.json) { request =>
    secureApi.invokeBlock(request, { secureRequest =>
      request.body.validate[BiosampleUpdate].fold(
        errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request format"))),
        update => {
          biosampleUpdateService.updateBiosample(id, update).map {
            case Right(biosample) => Ok(Json.toJson(biosample))
            case Left(error) => BadRequest(Json.obj("error" -> error))
          }
        }
      )
    })
  }

  /**
   * Retrieves all biosamples along with their associated studies.
   *
   * The method fetches data for all biosamples and enriches them with the corresponding
   * study information. The data is retrieved from the `biosampleRepository` and returned
   * as a JSON response.
   *
   * @return an asynchronous action resulting in an HTTP JSON response containing
   *         a list of biosamples with their associated studies
   */
  def getSamplesWithStudies: Action[AnyContent] = Action.async {
    biosampleRepository.findAllWithStudies().map {
      samples =>
        Ok(Json.toJson(samples))
    }
  }

  /**
   * Searches for a biosample based on the provided alias or accession identifier.
   *
   * The method queries the `biosampleRepository` to fetch a biosample associated with the given
   * alias or accession. If found, it returns a JSON response containing the biosample details.
   * Otherwise, it returns an HTTP 404 response indicating the biosample was not found.
   *
   * @param query the alias or accession identifier used to search for the biosample
   * @return an asynchronous action resulting in an HTTP JSON response:
   *         - On success, returns a JSON representation of the matched biosample.
   *         - On failure, returns a 404 response with an error message.
   */
  def findByAliasOrAccession(query: String): Action[AnyContent] = Action.async {
    biosampleRepository.findByAliasOrAccession(query).map {
      case Some((biosample, specimenDonor)) => Ok(Json.toJson(BiosampleView.fromDomain(biosample, specimenDonor)))
      case None => NotFound(Json.obj("error" -> "Biosample not found"))
    }
  }
}