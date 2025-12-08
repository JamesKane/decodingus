package controllers

import api.PdsRegistrationRequest
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.UserRepository
import services.PDSRegistrationService
import services.social.ReputationService
import play.api.Logging

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PDSRegistrationController @Inject()(
                                           val controllerComponents: ControllerComponents,
                                           pdsRegistrationService: PDSRegistrationService,
                                           reputationService: ReputationService,
                                           userRepository: UserRepository
                                         )(implicit ec: ExecutionContext) extends BaseController with Logging {

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
        ).flatMap {
          case Right(pdsRegistration) =>
            userRepository.findByDid(pdsRegistration.did).flatMap {
              case Some(user) =>
                reputationService.recordEvent(
                  userId = user.id.get, // Assuming user.id is always defined for a registered user
                  eventTypeName = "ACCOUNT_VERIFIED",
                  notes = Some("Initial PDS Registration")
                ).flatMap { _ =>
                  reputationService.recordEvent(
                    userId = user.id.get,
                    eventTypeName = "NEW_USER_BONUS",
                    notes = Some("Welcome Bonus")
                  )
                }.map { _ =>
                  Ok(Json.toJson(pdsRegistration))
                }
              case None =>
                // This case should ideally not happen if PDS registration implies an existing user.
                // Log an error and proceed without awarding reputation.
                logger.error(s"User not found for DID ${pdsRegistration.did} after successful PDS registration.")
                Future.successful(Ok(Json.toJson(pdsRegistration)))
            }
          case Left(errorMessage) =>
            Future.successful(BadRequest(Json.obj("error" -> errorMessage)))
        }
      case JsError(errors) =>
        Future.successful(BadRequest(Json.obj("error" -> "Invalid JSON body", "details" -> JsError.toJson(errors))))
    }
  }
}
