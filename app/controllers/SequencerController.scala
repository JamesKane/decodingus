package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.SequencerInstrumentRepository

import scala.concurrent.ExecutionContext

/**
 * Controller for sequencer instrument and lab information endpoints.
 */
@Singleton
class SequencerController @Inject()(
                                     cc: ControllerComponents,
                                     repository: SequencerInstrumentRepository,
                                     apiSecurityAction: ApiSecurityAction
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
}