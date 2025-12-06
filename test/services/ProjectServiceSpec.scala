package services

import models.api.{ProjectRequest, ProjectResponse}
import models.domain.Project
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import repositories.ProjectRepository

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class ProjectServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def createRequest(
    name: String = "Test Project",
    description: Option[String] = Some("A test project"),
    atUri: Option[String] = Some("at://did:plc:test/com.decodingus.atmosphere.project/rkey1"),
    atCid: Option[String] = None
  ): ProjectRequest = ProjectRequest(
    name = name,
    description = description,
    atUri = atUri,
    atCid = atCid
  )

  "ProjectService" should {

    "create a new project successfully" in {
      val mockRepo = mock[ProjectRepository]

      when(mockRepo.create(any[Project]))
        .thenAnswer(new Answer[Future[Project]] {
          override def answer(invocation: InvocationOnMock): Future[Project] = {
            val p = invocation.getArgument[Project](0)
            Future.successful(p.copy(id = Some(1)))
          }
        })

      val service = new ProjectService(mockRepo)
      val request = createRequest()

      whenReady(service.createProject(request)) { response =>
        response.name mustBe "Test Project"
        response.description mustBe Some("A test project")
        response.projectGuid mustBe a[UUID]
        response.atCid mustBe defined

        verify(mockRepo).create(any[Project])
      }
    }

    "update an existing project successfully" in {
      val mockRepo = mock[ProjectRepository]
      val existingGuid = UUID.randomUUID()
      val existingAtCid = "existing-cid-123"
      val atUri = "at://did:plc:test/com.decodingus.atmosphere.project/rkey1"

      val existingProject = Project(
        id = Some(1),
        projectGuid = existingGuid,
        name = "Old Name",
        description = Some("Old description"),
        ownerDid = "did:example:owner",
        createdAt = LocalDateTime.now().minusDays(1),
        updatedAt = LocalDateTime.now().minusDays(1),
        deleted = false,
        atUri = Some(atUri),
        atCid = Some(existingAtCid)
      )

      when(mockRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(Some(existingProject)))

      when(mockRepo.update(any[Project], any[Option[String]]))
        .thenReturn(Future.successful(true))

      val service = new ProjectService(mockRepo)
      val request = createRequest(
        name = "Updated Name",
        description = Some("Updated description"),
        atUri = Some(atUri),
        atCid = Some(existingAtCid)
      )

      whenReady(service.updateProject(atUri, request)) { response =>
        response.name mustBe "Updated Name"
        response.description mustBe Some("Updated description")
        response.projectGuid mustBe existingGuid
        response.atCid mustBe defined
        response.atCid must not be Some(existingAtCid) // Should be new CID

        verify(mockRepo).update(any[Project], any[Option[String]])
      }
    }

    "fail update with optimistic locking error when atCid mismatch" in {
      val mockRepo = mock[ProjectRepository]
      val atUri = "at://did:plc:test/com.decodingus.atmosphere.project/rkey1"

      val existingProject = Project(
        id = Some(1),
        projectGuid = UUID.randomUUID(),
        name = "Project",
        description = None,
        ownerDid = "did:example:owner",
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
        deleted = false,
        atUri = Some(atUri),
        atCid = Some("current-cid")
      )

      when(mockRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(Some(existingProject)))

      val service = new ProjectService(mockRepo)
      val request = createRequest(atCid = Some("stale-cid"))

      whenReady(service.updateProject(atUri, request).failed) { e =>
        e mustBe a[IllegalStateException]
        e.getMessage must include("Optimistic locking failure")

        verify(mockRepo, never).update(any[Project], any[Option[String]])
      }
    }

    "fail update when project not found" in {
      val mockRepo = mock[ProjectRepository]
      val atUri = "at://did:plc:test/com.decodingus.atmosphere.project/nonexistent"

      when(mockRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(None))

      val service = new ProjectService(mockRepo)
      val request = createRequest()

      whenReady(service.updateProject(atUri, request).failed) { e =>
        e mustBe a[NoSuchElementException]
        e.getMessage must include("not found")
      }
    }

    "delete a project successfully" in {
      val mockRepo = mock[ProjectRepository]
      val atUri = "at://did:plc:test/com.decodingus.atmosphere.project/rkey1"

      when(mockRepo.softDeleteByAtUri(atUri))
        .thenReturn(Future.successful(true))

      val service = new ProjectService(mockRepo)

      whenReady(service.deleteProject(atUri)) { result =>
        result mustBe true
        verify(mockRepo).softDeleteByAtUri(atUri)
      }
    }

    "return false when deleting non-existent project" in {
      val mockRepo = mock[ProjectRepository]
      val atUri = "at://did:plc:test/com.decodingus.atmosphere.project/nonexistent"

      when(mockRepo.softDeleteByAtUri(atUri))
        .thenReturn(Future.successful(false))

      val service = new ProjectService(mockRepo)

      whenReady(service.deleteProject(atUri)) { result =>
        result mustBe false
      }
    }

    "allow update without atCid (no optimistic locking check)" in {
      val mockRepo = mock[ProjectRepository]
      val atUri = "at://did:plc:test/com.decodingus.atmosphere.project/rkey1"

      val existingProject = Project(
        id = Some(1),
        projectGuid = UUID.randomUUID(),
        name = "Project",
        description = None,
        ownerDid = "did:example:owner",
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
        deleted = false,
        atUri = Some(atUri),
        atCid = Some("any-cid")
      )

      when(mockRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(Some(existingProject)))

      when(mockRepo.update(any[Project], any[Option[String]]))
        .thenReturn(Future.successful(true))

      val service = new ProjectService(mockRepo)
      // Request without atCid - should skip optimistic locking check
      val request = createRequest(atCid = None)

      whenReady(service.updateProject(atUri, request)) { response =>
        response.name mustBe "Test Project"
        verify(mockRepo).update(any[Project], any[Option[String]])
      }
    }
  }
}
