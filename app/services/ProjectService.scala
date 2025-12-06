package services

import jakarta.inject.{Inject, Singleton}
import models.api.{ProjectRequest, ProjectResponse}
import models.domain.Project
import repositories.ProjectRepository

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ProjectService @Inject()(
                                projectRepository: ProjectRepository
                              )(implicit ec: ExecutionContext) {

  def createProject(request: ProjectRequest): Future[ProjectResponse] = {
    val project = Project(
      id = None,
      projectGuid = UUID.randomUUID(),
      name = request.name,
      description = request.description,
      ownerDid = "did:example:owner", // Placeholder until auth provides owner DID
      createdAt = LocalDateTime.now(),
      updatedAt = LocalDateTime.now(),
      deleted = false,
      atUri = request.atUri,
      atCid = Some(UUID.randomUUID().toString)
    )

    projectRepository.create(project).map(toResponse)
  }

  def updateProject(atUri: String, request: ProjectRequest): Future[ProjectResponse] = {
    projectRepository.findByAtUri(atUri).flatMap {
      case Some(existing) =>
        if (request.atCid.isDefined && request.atCid != existing.atCid) {
          Future.failed(new IllegalStateException(s"Optimistic locking failure: atCid mismatch."))
        } else {
          val updatedProject = existing.copy(
            name = request.name,
            description = request.description,
            atUri = request.atUri,
            updatedAt = LocalDateTime.now(),
            atCid = Some(UUID.randomUUID().toString)
          )
          projectRepository.update(updatedProject, request.atCid).flatMap { success =>
            if (success) {
              Future.successful(toResponse(updatedProject))
            } else {
              Future.failed(new RuntimeException("Update failed"))
            }
          }
        }
      case None =>
        Future.failed(new NoSuchElementException(s"Project not found for atUri: $atUri"))
    }
  }

  def deleteProject(atUri: String): Future[Boolean] = {
    projectRepository.softDeleteByAtUri(atUri)
  }

  private def toResponse(p: Project): ProjectResponse = {
    ProjectResponse(
      projectGuid = p.projectGuid,
      name = p.name,
      description = p.description,
      ownerDid = p.ownerDid,
      createdAt = p.createdAt,
      updatedAt = p.updatedAt,
      atCid = p.atCid
    )
  }
}
