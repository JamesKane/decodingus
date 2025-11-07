package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.AssociateLabWithInstrumentRequest
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.SequencerInstrumentRepository
import services.genomics.SequencerInstrumentService

import scala.concurrent.ExecutionContext

/**
 * Controller for sequencer instrument and lab information endpoints.
 */
@Singleton
class SequencerController @Inject()(
                                     cc: ControllerComponents,
                                     repository: SequencerInstrumentRepository,
                                     apiSecurityAction: ApiSecurityAction,
                                     sequencerService: SequencerInstrumentService
                                   )(implicit ec: ExecutionContext)
  extends AbstractController(cc) {

  /**
   * Endpoint: GET /api/v1/sequencer/lab?instrument_id={id}
   *
   * Returns lab information for the given instrument ID.
   */
  def getLabByInstrumentId(instrumentId: String): Action[AnyContent] = Action.async { implicit request =>
    repository.findLabByInstrumentId(instrumentId).map {
      case Some(labInfo) =>
        Ok(Json.toJson(labInfo))
      case None =>
        NotFound(s"Instrument ID '$instrumentId' not found")
    }
  }

  /**
   * Endpoint: POST /api/v1/sequencer/lab/associate
   *
   * Associates a lab with an instrument ID. If the lab doesn't exist,
   * a placeholder record is created that can be updated with additional metadata later.
   *
   * Requires API Key authentication.
   */
  def associateLabWithInstrument(): Action[AssociateLabWithInstrumentRequest] =
    Action.async(parse.json[AssociateLabWithInstrumentRequest]) { request =>
      apiSecurityAction.invokeBlock(request, { secureRequest =>
        sequencerService.associateLabWithInstrument(
          request.body.instrumentId,
          request.body.labName
        ).map { result =>
          Ok(Json.toJson(result))
        }.recover {
          case e: IllegalArgumentException =>
            BadRequest(Json.obj("error" -> e.getMessage))
          case e: Exception =>
            InternalServerError(Json.obj("error" -> "Failed to associate lab with instrument"))
        }
      })
    }
}