package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.*
import services.firehose.{AtmosphereEventHandler, BiosampleEvent, SequenceRunEvent, AlignmentEvent, AtmosphereProjectEvent, FirehoseEvent, FirehoseResult}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json.JsValue

@Singleton
class CitizenBiosampleController @Inject()(
                                            val controllerComponents: ControllerComponents,
                                            secureApi: ApiSecurityAction,
                                            citizenBiosampleService: CitizenBiosampleService,
                                            atmosphereEventHandler: AtmosphereEventHandler
                                          )(implicit ec: ExecutionContext) extends BaseController {

  def create: Action[ExternalBiosampleRequest] = secureApi.jsonAction[ExternalBiosampleRequest].async { request =>
    citizenBiosampleService.createBiosample(request.body).map { guid =>
      Created(Json.obj(
        "status" -> "success",
        "guid" -> guid
      ))
    }.recover {
      case e: DuplicateAccessionException =>
        Conflict(Json.obj("error" -> "Duplicate accession", "message" -> e.getMessage))
      case e: InvalidCoordinatesException =>
        BadRequest(Json.obj("error" -> "Invalid coordinates", "message" -> e.getMessage))
      case e: SequenceDataValidationException =>
        BadRequest(Json.obj("error" -> "Invalid sequence data", "message" -> e.getMessage))
      case e: PublicationLinkageException =>
        BadRequest(Json.obj("error" -> "Publication linkage failed", "message" -> e.getMessage))
      case e: BiosampleServiceException =>
        BadRequest(Json.obj("error" -> "Validation error", "message" -> e.getMessage))
      case e: IllegalArgumentException =>
        Conflict(Json.obj("error" -> "Conflict", "message" -> e.getMessage))
      case e: Exception =>
        InternalServerError(Json.obj("error" -> "Internal server error", "message" -> e.getMessage))
    }
  }

  def update(atUri: String): Action[ExternalBiosampleRequest] = secureApi.jsonAction[ExternalBiosampleRequest].async { request =>
    citizenBiosampleService.updateBiosample(atUri, request.body).map { guid =>
      Ok(Json.obj(
        "status" -> "success",
        "guid" -> guid
      ))
    }.recover {
      case e: IllegalStateException =>
        Conflict(Json.obj("error" -> "Optimistic locking failure", "message" -> e.getMessage))
      case e: NoSuchElementException =>
        NotFound(Json.obj("error" -> "Biosample not found", "message" -> e.getMessage))
      case e: InvalidCoordinatesException =>
        BadRequest(Json.obj("error" -> "Invalid coordinates", "message" -> e.getMessage))
      case e: Exception =>
        InternalServerError(Json.obj("error" -> "Internal server error", "message" -> e.getMessage))
    }
  }

  def delete(atUri: String): Action[AnyContent] = secureApi.async { request =>
    citizenBiosampleService.deleteBiosample(atUri).map {
      case true => NoContent
      case false => NotFound(Json.obj("error" -> "Biosample not found", "message" -> s"Biosample with atUri '$atUri' not found."))
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj(
          "error" -> "Internal server error",
          "message" -> s"An unexpected error occurred while attempting to delete biosample: ${e.getMessage}"
        ))
    }
  }

  def processEvent: Action[JsValue] = secureApi.jsonAction[JsValue].async { request =>
    val json = request.body
    
    // Attempt to parse the JSON into one of the known FirehoseEvent types
    // This is a simple heuristic based on which parse succeeds first.
    // Ideally, we would use a discriminator field (like '$type') in the JSON.
    val event: Option[FirehoseEvent] = 
      json.validate[BiosampleEvent].asOpt
        .orElse(json.validate[SequenceRunEvent].asOpt)
        .orElse(json.validate[AlignmentEvent].asOpt)
        .orElse(json.validate[AtmosphereProjectEvent].asOpt)
        // Add other event types here as needed (GenotypeEvent, etc.)

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