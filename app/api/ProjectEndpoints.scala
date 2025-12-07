package api

import models.api.{ProjectRequest, ProjectResponse}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

import java.util.UUID

object ProjectEndpoints {

  private val createProject: PublicEndpoint[ProjectRequest, String, ProjectResponse, Any] = {
    endpoint
      .post
      .in("api" / "projects")
      .in(jsonBody[ProjectRequest])
      .out(jsonBody[ProjectResponse])
      .errorOut(stringBody)
      .description("Creates a new Project. (Deprecated: Use /api/firehose/event)")
      .summary("Create Project (Legacy)")
      .tag("Projects")
      .deprecated()
  }

  private val updateProject: PublicEndpoint[(String, ProjectRequest), String, ProjectResponse, Any] = {
    endpoint
      .put
      .in("api" / "projects" / path[String]("atUri"))
      .in(jsonBody[ProjectRequest])
      .out(jsonBody[ProjectResponse])
      .errorOut(stringBody)
      .description("Updates an existing Project using Optimistic Locking (via atCid). (Deprecated: Use /api/firehose/event)")
      .summary("Update Project (Legacy)")
      .tag("Projects")
      .deprecated()
  }

  private val deleteProject: PublicEndpoint[String, String, Unit, Any] = {
    endpoint
      .delete
      .in("api" / "projects" / path[String]("atUri"))
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(stringBody)
      .description("Soft deletes a Project. (Deprecated: Use /api/firehose/event)")
      .summary("Delete Project (Legacy)")
      .tag("Projects")
      .deprecated()
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    createProject,
    updateProject,
    deleteProject
  )
}
