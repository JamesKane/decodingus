package services

import models.PDSRegistration
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import repositories.PDSRegistrationRepository

import java.time.ZonedDateTime
import scala.concurrent.{ExecutionContext, Future}

class PDSRegistrationServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def createMocks(): (ATProtocolClient, PDSRegistrationRepository) = {
    (mock[ATProtocolClient], mock[PDSRegistrationRepository])
  }

  "PDSRegistrationService" should {

    "register a new PDS successfully" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:test123"
      val handle = "user.bsky.social"
      val pdsUrl = "https://pds.example.com"
      val rToken = "auth-token"

      // PDS not already registered
      when(repo.findByDid(did))
        .thenReturn(Future.successful(None))

      // AT Protocol verification succeeds
      when(atClient.getLatestCommit(pdsUrl, did, rToken))
        .thenReturn(Future.successful(Some(LatestCommitResponse(
          cid = "bafyreib123",
          rev = "rev-001",
          seq = 42L
        ))))

      // Repository create succeeds
      when(repo.create(any[PDSRegistration]))
        .thenAnswer(new Answer[Future[PDSRegistration]] {
          override def answer(invocation: InvocationOnMock): Future[PDSRegistration] = {
            Future.successful(invocation.getArgument[PDSRegistration](0))
          }
        })

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.registerPDS(did, handle, pdsUrl, rToken)) { result =>
        result mustBe a[Right[_, _]]
        val registration = result.toOption.get
        registration.did mustBe did
        registration.pdsUrl mustBe pdsUrl
        registration.handle mustBe handle
        registration.lastCommitCid mustBe Some("bafyreib123")
        registration.lastCommitSeq mustBe Some(42L)

        verify(repo).create(any[PDSRegistration])
      }
    }

    "fail registration when PDS already registered" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:existing"
      val existingRegistration = PDSRegistration(
        did = did,
        pdsUrl = "https://existing.pds.com",
        handle = "existing.user",
        lastCommitCid = Some("abc"),
        lastCommitSeq = Some(10L),
        cursor = 0L,
        createdAt = ZonedDateTime.now(),
        updatedAt = ZonedDateTime.now()
      )

      when(repo.findByDid(did))
        .thenReturn(Future.successful(Some(existingRegistration)))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.registerPDS(did, "handle", "https://pds.com", "token")) { result =>
        result mustBe a[Left[_, _]]
        result.left.getOrElse("") must include("already registered")

        verify(atClient, never).getLatestCommit(anyString(), anyString(), anyString())
        verify(repo, never).create(any[PDSRegistration])
      }
    }

    "fail registration when AT Protocol verification fails" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:unverifiable"
      val pdsUrl = "https://unreachable.pds.com"

      when(repo.findByDid(did))
        .thenReturn(Future.successful(None))

      // AT Protocol verification fails
      when(atClient.getLatestCommit(pdsUrl, did, "token"))
        .thenReturn(Future.successful(None))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.registerPDS(did, "handle", pdsUrl, "token")) { result =>
        result mustBe a[Left[_, _]]
        result.left.getOrElse("") must include("Failed to verify")

        verify(repo, never).create(any[PDSRegistration])
      }
    }

    "retrieve PDS by DID" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:test123"
      val registration = PDSRegistration(
        did = did,
        pdsUrl = "https://pds.example.com",
        handle = "user.test",
        lastCommitCid = Some("cid"),
        lastCommitSeq = Some(100L),
        cursor = 50L,
        createdAt = ZonedDateTime.now(),
        updatedAt = ZonedDateTime.now()
      )

      when(repo.findByDid(did))
        .thenReturn(Future.successful(Some(registration)))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.getPDSByDid(did)) { result =>
        result mustBe defined
        result.get.did mustBe did
        result.get.handle mustBe "user.test"
      }
    }

    "retrieve PDS by handle" in {
      val (atClient, repo) = createMocks()

      val handle = "user.bsky.social"
      val registration = PDSRegistration(
        did = "did:plc:abc",
        pdsUrl = "https://pds.example.com",
        handle = handle,
        lastCommitCid = None,
        lastCommitSeq = None,
        cursor = 0L,
        createdAt = ZonedDateTime.now(),
        updatedAt = ZonedDateTime.now()
      )

      when(repo.findByHandle(handle))
        .thenReturn(Future.successful(Some(registration)))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.getPDSByHandle(handle)) { result =>
        result mustBe defined
        result.get.handle mustBe handle
      }
    }

    "list all registered PDS entries" in {
      val (atClient, repo) = createMocks()

      val registrations = Seq(
        PDSRegistration("did:1", "https://pds1.com", "user1", None, None, 0L, ZonedDateTime.now(), ZonedDateTime.now()),
        PDSRegistration("did:2", "https://pds2.com", "user2", None, None, 0L, ZonedDateTime.now(), ZonedDateTime.now())
      )

      when(repo.listAll)
        .thenReturn(Future.successful(registrations))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.listAllPDS()) { result =>
        result must have size 2
        result.map(_.did) must contain allOf("did:1", "did:2")
      }
    }

    "update PDS cursor successfully" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:test"
      val newCid = "newCid123"
      val newCursor = 200L

      when(repo.updateCursor(did, newCid, newCursor))
        .thenReturn(Future.successful(1))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.updatePDSCursor(did, newCid, newCursor)) { result =>
        result mustBe Right(())
        verify(repo).updateCursor(did, newCid, newCursor)
      }
    }

    "fail cursor update when PDS not found" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:nonexistent"

      when(repo.updateCursor(did, "cid", 100L))
        .thenReturn(Future.successful(0)) // No rows affected

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.updatePDSCursor(did, "cid", 100L)) { result =>
        result mustBe a[Left[_, _]]
        result.left.getOrElse("") must include("not found")
      }
    }

    "delete PDS registration successfully" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:todelete"

      when(repo.delete(did))
        .thenReturn(Future.successful(1))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.deletePDS(did)) { result =>
        result mustBe Right(())
        verify(repo).delete(did)
      }
    }

    "fail deletion when PDS not found" in {
      val (atClient, repo) = createMocks()

      val did = "did:plc:nonexistent"

      when(repo.delete(did))
        .thenReturn(Future.successful(0))

      val service = new PDSRegistrationService(atClient, repo)

      whenReady(service.deletePDS(did)) { result =>
        result mustBe a[Left[_, _]]
        result.left.getOrElse("") must include("not found")
      }
    }
  }
}
