package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.firehose.{AtmosphereEventHandler, FirehoseEvent, FirehoseResult} // Removed individual event imports

import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json.JsValue

@Singleton
class FirehoseController @Inject()(
                                    val controllerComponents: ControllerComponents,
                                    secureApi: ApiSecurityAction,
                                    atmosphereEventHandler: AtmosphereEventHandler
                                  )(implicit ec: ExecutionContext) extends BaseController {

  def processEvent: Action[JsValue] = secureApi.jsonAction[JsValue].async { request =>
    val json = request.body
    
    // Use the discriminator-based JSON Reads for FirehoseEvent
    val event: Option[FirehoseEvent] = json.validate[FirehoseEvent].asOpt

    event match {
      case Some(e) =>
        atmosphereEventHandler.handle(e).map {
          case FirehoseResult.Success(_, _, guid, msg) => 
            Ok(Json.obj("status" -> "success", "message" -> msg, "guid" -> guid))
          case FirehoseResult.Conflict(_, msg) => 
            Conflict(Json.obj("error" -> msg))
          case FirehoseResult.NotFound(uri) => 
            NotFound(Json.obj("error" -> s"Not found: $uri"))
          case FirehoseResult.ValidationError(_, msg) => 
            BadRequest(Json.obj("error" -> msg))
          case FirehoseResult.Error(_, msg, _) => 
            InternalServerError(Json.obj("error" -> msg))
        }
      case None =>
        Future.successful(BadRequest(Json.obj("error" -> "Unknown or invalid event structure")))
    }
  }
}