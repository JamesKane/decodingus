package controllers

import api.PdsRegistrationRequest
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.PDSRegistrationService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PDSRegistrationController @Inject()(
                                           val controllerComponents: ControllerComponents,
                                           pdsRegistrationService: PDSRegistrationService
                                         )(implicit ec: ExecutionContext) extends BaseController {

  /**
   * Handles the registration of a new Personal Data Server (PDS).
   * Expects a JSON body containing PdsRegistrationRequest.
   *
   * @return An `Action` that processes the registration request.
   */
  def registerPDS(): Action[play.api.libs.json.JsValue] = Action.async(parse.json) { implicit request =>
    request.body.validate[PdsRegistrationRequest] match {
      case JsSuccess(pdsRegistrationRequest, _) =>
        pdsRegistrationService.registerPDS(
          pdsRegistrationRequest.did,
          pdsRegistrationRequest.handle,
          pdsRegistrationRequest.pdsUrl,
          pdsRegistrationRequest.rToken
        ).map {
          case Right(pdsRegistration) =>
            Ok(Json.toJson(pdsRegistration))
          case Left(errorMessage) =>
            BadRequest(Json.obj("error" -> errorMessage))
        }
      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("error" -> "Invalid JSON body", "details" -> JsError.toJson(errors))))
    }
  }
}
