package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.ProjectRequest
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.ProjectService

import java.util.UUID
import scala.concurrent.ExecutionContext

@Singleton
class ProjectController @Inject()(
                                   val controllerComponents: ControllerComponents,
                                   secureApi: ApiSecurityAction,
                                   projectService: ProjectService
                                 )(implicit ec: ExecutionContext) extends BaseController {

  @Deprecated("Use Firehose Event API instead. This endpoint will be removed in a future release.")
  def create: Action[ProjectRequest] = secureApi.jsonAction[ProjectRequest].async { request =>
    projectService.createProject(request.body).map { response =>
      Created(Json.toJson(response))
    }.recover {
      case e: Exception => InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  @Deprecated("Use Firehose Event API instead. This endpoint will be removed in a future release.")
  def update(atUri: String): Action[ProjectRequest] = secureApi.jsonAction[ProjectRequest].async { request =>
    projectService.updateProject(atUri, request.body).map { response =>
      Ok(Json.toJson(response))
    }.recover {
      case e: IllegalStateException => Conflict(Json.obj("error" -> e.getMessage))
      case e: NoSuchElementException => NotFound(Json.obj("error" -> e.getMessage))
      case e: Exception => InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  @Deprecated("Use Firehose Event API instead. This endpoint will be removed in a future release.")
  def delete(atUri: String): Action[AnyContent] = secureApi.async { request =>
    projectService.deleteProject(atUri).map {
      case true => NoContent
      case false => NotFound(Json.obj("error" -> "Project not found"))
    }.recover {
      case e: Exception => InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }
}
