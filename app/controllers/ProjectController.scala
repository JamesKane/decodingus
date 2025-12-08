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

}
