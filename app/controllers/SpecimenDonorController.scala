package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.SpecimenDonorMergeRequest
import play.api.libs.json.Json
import play.api.mvc.*
import services.genomics.SpecimenDonorService

import scala.concurrent.ExecutionContext

/**
 * Controller responsible for handling operations related to specimen donors.
 *
 * This controller provides endpoints for managing donor records, including
 * the ability to merge multiple donors into a single unified record.
 *
 * @constructor Creates an instance of SpecimenDonorController
 * @param donorService the service used to manage and merge specimen donor data
 * @param secureApi    the security action used to secure API endpoints
 * @param cc           controller components needed to construct the controller
 * @param ec           the execution context for handling asynchronous operations
 */
@Singleton
class SpecimenDonorController @Inject()(
                                         donorService: SpecimenDonorService,
                                         secureApi: ApiSecurityAction,
                                         cc: ControllerComponents
                                       )(implicit ec: ExecutionContext) extends AbstractController(cc) {

  /**
   * Merges multiple specimen donor records into a single unified record.
   *
   * This method handles the merging of donor data by using a secure API invocation.
   * The merging process is performed based on the details provided in the request body,
   * which includes the target donor ID, a list of source donor IDs, and the merge strategy.
   * It returns the result of the operation or an appropriate error response in case of failure.
   *
   * @return An asynchronous `Action` that processes a `SpecimenDonorMergeRequest`
   *         and yields a response containing the result of the donor merge operation.
   */
  def mergeDonors(): Action[SpecimenDonorMergeRequest] = Action.async(parse.json[SpecimenDonorMergeRequest]) { request =>
    secureApi.invokeBlock(request, { secureRequest =>
      donorService.mergeDonors(request.body).map { result =>
        Ok(Json.toJson(result))
      }.recover {
        case e: IllegalArgumentException => BadRequest(Json.obj("error" -> e.getMessage))
        case e: Exception => InternalServerError(Json.obj("error" -> "Failed to merge donors"))
      }
    })
  }
}
