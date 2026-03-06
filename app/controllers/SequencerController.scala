package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.AssociateLabWithInstrumentRequest
import models.api.SequencerLabInstrumentsResponse
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import services.genomics.SequencerInstrumentService

import scala.concurrent.ExecutionContext

@Singleton
class SequencerController @Inject()(
                                     cc: ControllerComponents,
                                     apiSecurityAction: ApiSecurityAction,
                                     sequencerService: SequencerInstrumentService
                                   )(implicit ec: ExecutionContext)
  extends AbstractController(cc) {

  def getLabByInstrumentId(instrumentId: String): Action[AnyContent] = Action.async { implicit request =>
    sequencerService.lookupLab(instrumentId).map {
      case Some(lookupResult) =>
        Ok(Json.toJson(lookupResult))
      case None =>
        NotFound(Json.obj("error" -> s"No lab association found for instrument '$instrumentId'"))
    }
  }

  def getAllLabInstruments: Action[AnyContent] = Action.async { implicit request =>
    sequencerService.getAllLabInstrumentAssociations.map { associations =>
      Ok(Json.toJson(SequencerLabInstrumentsResponse(
        data = associations,
        count = associations.length
      )))
    }.recover {
      case _: Exception =>
        InternalServerError(Json.obj("error" -> "Failed to retrieve lab-instrument associations"))
    }
  }

  def associateLabWithInstrument(): Action[AssociateLabWithInstrumentRequest] =
    Action.async(parse.json[AssociateLabWithInstrumentRequest]) { request =>
      apiSecurityAction.invokeBlock(request, { _ =>
        sequencerService.associateLabWithInstrument(
          request.body.instrumentId,
          request.body.labName,
          request.body.manufacturer,
          request.body.model
        ).map { result =>
          Ok(Json.toJson(result))
        }.recover {
          case e: IllegalArgumentException =>
            BadRequest(Json.obj("error" -> e.getMessage))
          case _: Exception =>
            InternalServerError(Json.obj("error" -> "Failed to associate lab with instrument"))
        }
      })
    }
}
