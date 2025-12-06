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
      .description("Creates a new Project.")
      .summary("Create Project")
      .tag("Projects")
  }

  private val updateProject: PublicEndpoint[(UUID, ProjectRequest), String, ProjectResponse, Any] = {
    endpoint
      .put
      .in("api" / "projects" / path[UUID]("projectGuid"))
      .in(jsonBody[ProjectRequest])
      .out(jsonBody[ProjectResponse])
      .errorOut(stringBody)
      .description("Updates an existing Project using Optimistic Locking (via atCid).")
      .summary("Update Project")
      .tag("Projects")
  }

  private val deleteProject: PublicEndpoint[UUID, String, Unit, Any] = {
    endpoint
      .delete
      .in("api" / "projects" / path[UUID]("projectGuid"))
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(stringBody)
      .description("Soft deletes a Project.")
      .summary("Delete Project")
      .tag("Projects")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    createProject,
    updateProject,
    deleteProject
  )
}
