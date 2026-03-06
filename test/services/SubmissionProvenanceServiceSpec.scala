package services

import helpers.ServiceSpec
import models.domain.pds.{PdsNode, PdsSubmission}
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{never, reset, verify, when}
import play.api.libs.json.Json
import repositories.{PdsNodeRepository, PdsSubmissionRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class SubmissionProvenanceServiceSpec extends ServiceSpec {

  val mockSubmissionRepo: PdsSubmissionRepository = mock[PdsSubmissionRepository]
  val mockNodeRepo: PdsNodeRepository = mock[PdsNodeRepository]

  val service = new SubmissionProvenanceService(mockSubmissionRepo, mockNodeRepo)

  override def beforeEach(): Unit = {
    reset(mockSubmissionRepo, mockNodeRepo)
  }

  val now: LocalDateTime = LocalDateTime.now()

  val testNode: PdsNode = PdsNode(
    id = Some(1), did = "did:plc:user1", pdsUrl = "https://pds.user1.example.com",
    softwareVersion = Some("0.1.0"), status = "ONLINE"
  )

  val testSubmission: PdsSubmission = PdsSubmission(
    id = Some(1), pdsNodeId = 1, submissionType = "HAPLOGROUP_CALL",
    biosampleId = Some(100), proposedValue = "R-CTS4466",
    confidenceScore = Some(0.95), algorithmVersion = Some("v2.1"),
    softwareVersion = Some("0.1.0"), status = "PENDING"
  )

  val sampleGuid: UUID = UUID.randomUUID()

  "SubmissionProvenanceService.recordSubmission" should {

    "record a haplogroup call submission" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockSubmissionRepo.create(any[PdsSubmission]))
        .thenReturn(Future.successful(testSubmission))

      val result = service.recordSubmission(
        did = "did:plc:user1",
        submissionType = "HAPLOGROUP_CALL",
        proposedValue = "R-CTS4466",
        biosampleId = Some(100),
        confidenceScore = Some(0.95),
        algorithmVersion = Some("v2.1")
      ).futureValue

      result.isRight mustBe true
      result.toOption.get.submissionType mustBe "HAPLOGROUP_CALL"
      result.toOption.get.proposedValue mustBe "R-CTS4466"
      verify(mockSubmissionRepo).create(any[PdsSubmission])
    }

    "record a variant call submission with payload" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      val payload = Json.obj("position" -> 12345, "ref" -> "A", "alt" -> "G")
      when(mockSubmissionRepo.create(any[PdsSubmission]))
        .thenReturn(Future.successful(testSubmission.copy(submissionType = "VARIANT_CALL",
          proposedValue = "chrY:12345:A>G", payload = Some(payload))))

      val result = service.recordSubmission(
        did = "did:plc:user1",
        submissionType = "VARIANT_CALL",
        proposedValue = "chrY:12345:A>G",
        payload = Some(payload)
      ).futureValue

      result.isRight mustBe true
    }

    "record a submission with biosample GUID" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockSubmissionRepo.create(any[PdsSubmission]))
        .thenReturn(Future.successful(testSubmission.copy(biosampleGuid = Some(sampleGuid))))

      val result = service.recordSubmission(
        did = "did:plc:user1",
        submissionType = "HAPLOGROUP_CALL",
        proposedValue = "R-L151",
        biosampleGuid = Some(sampleGuid)
      ).futureValue

      result.isRight mustBe true
    }

    "inherit software version from node when not specified" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockSubmissionRepo.create(any[PdsSubmission]))
        .thenAnswer(inv => Future.successful(inv.getArgument[PdsSubmission](0).copy(id = Some(5))))

      val result = service.recordSubmission(
        did = "did:plc:user1",
        submissionType = "HAPLOGROUP_CALL",
        proposedValue = "R-M269"
      ).futureValue

      result.isRight mustBe true
      result.toOption.get.softwareVersion mustBe Some("0.1.0")
    }

    "reject invalid submission type" in {
      val result = service.recordSubmission(
        did = "did:plc:user1",
        submissionType = "INVALID",
        proposedValue = "test"
      ).futureValue

      result mustBe Left("Invalid submission type: INVALID")
      verify(mockNodeRepo, never()).findByDid(any())
    }

    "reject submission from unregistered PDS" in {
      when(mockNodeRepo.findByDid("did:plc:unknown")).thenReturn(Future.successful(None))

      val result = service.recordSubmission(
        did = "did:plc:unknown",
        submissionType = "HAPLOGROUP_CALL",
        proposedValue = "R-M269"
      ).futureValue

      result mustBe Left("PDS node not registered: did:plc:unknown")
    }
  }

  "SubmissionProvenanceService.acceptSubmission" should {

    "accept a pending submission" in {
      when(mockSubmissionRepo.findById(1)).thenReturn(Future.successful(Some(testSubmission)))
      when(mockSubmissionRepo.updateStatus(meq(1), meq("ACCEPTED"), meq(Some("curator1")), any()))
        .thenReturn(Future.successful(true))

      val result = service.acceptSubmission(1, "curator1", Some("Verified")).futureValue
      result mustBe Right(true)
    }

    "reject accepting a non-pending submission" in {
      val accepted = testSubmission.copy(status = "ACCEPTED")
      when(mockSubmissionRepo.findById(1)).thenReturn(Future.successful(Some(accepted)))

      val result = service.acceptSubmission(1, "curator1").futureValue
      result mustBe Left("Cannot update submission with status: ACCEPTED")
    }

    "return error for non-existent submission" in {
      when(mockSubmissionRepo.findById(999)).thenReturn(Future.successful(None))

      val result = service.acceptSubmission(999, "curator1").futureValue
      result mustBe Left("Submission not found")
    }
  }

  "SubmissionProvenanceService.rejectSubmission" should {

    "reject a pending submission with notes" in {
      when(mockSubmissionRepo.findById(1)).thenReturn(Future.successful(Some(testSubmission)))
      when(mockSubmissionRepo.updateStatus(meq(1), meq("REJECTED"), meq(Some("curator1")),
        meq(Some("Low confidence")))).thenReturn(Future.successful(true))

      val result = service.rejectSubmission(1, "curator1", Some("Low confidence")).futureValue
      result mustBe Right(true)
    }
  }

  "SubmissionProvenanceService.supersedeSubmission" should {

    "supersede a pending submission" in {
      when(mockSubmissionRepo.findById(1)).thenReturn(Future.successful(Some(testSubmission)))
      when(mockSubmissionRepo.updateStatus(meq(1), meq("SUPERSEDED"), meq(Some("system")), any()))
        .thenReturn(Future.successful(true))

      val result = service.supersedeSubmission(1, "system", Some("Newer call available")).futureValue
      result mustBe Right(true)
    }
  }

  "SubmissionProvenanceService.getSubmissionsForNode" should {

    "return submissions for a node" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockSubmissionRepo.findByNode(any[Int], any[Int]))
        .thenReturn(Future.successful(Seq(testSubmission)))

      val result = service.getSubmissionsForNode("did:plc:user1").futureValue
      result.isRight mustBe true
      result.toOption.get.size mustBe 1
    }

    "filter by submission type" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockSubmissionRepo.findByNodeAndType(1, "VARIANT_CALL"))
        .thenReturn(Future.successful(Seq.empty))

      val result = service.getSubmissionsForNode("did:plc:user1", Some("VARIANT_CALL")).futureValue
      result.isRight mustBe true
      result.toOption.get mustBe empty
    }

    "return error for unknown node" in {
      when(mockNodeRepo.findByDid("did:plc:unknown")).thenReturn(Future.successful(None))

      val result = service.getSubmissionsForNode("did:plc:unknown").futureValue
      result mustBe Left("PDS node not found")
    }
  }

  "SubmissionProvenanceService.getPendingSubmissions" should {

    "return all pending submissions" in {
      when(mockSubmissionRepo.findByStatus("PENDING", 100))
        .thenReturn(Future.successful(Seq(testSubmission)))

      val result = service.getPendingSubmissions().futureValue
      result.size mustBe 1
    }

    "filter pending by type" in {
      when(mockSubmissionRepo.findByTypeAndStatus("BRANCH_PROPOSAL", "PENDING", 50))
        .thenReturn(Future.successful(Seq.empty))

      val result = service.getPendingSubmissions(Some("BRANCH_PROPOSAL"), 50).futureValue
      result mustBe empty
    }
  }

  "SubmissionProvenanceService.getNodeSubmissionSummary" should {

    "compute submission summary for a node" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockSubmissionRepo.countByNodeAndStatus(1)).thenReturn(Future.successful(
        Map("PENDING" -> 3, "ACCEPTED" -> 15, "REJECTED" -> 2, "SUPERSEDED" -> 1)
      ))

      val result = service.getNodeSubmissionSummary("did:plc:user1").futureValue
      result.isRight mustBe true

      val summary = result.toOption.get
      summary.totalSubmissions mustBe 21
      summary.pendingCount mustBe 3
      summary.acceptedCount mustBe 15
      summary.rejectedCount mustBe 2
      summary.acceptanceRate mustBe (15.0 / 17.0) +- 0.001
    }

    "handle node with no submissions" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockSubmissionRepo.countByNodeAndStatus(1)).thenReturn(Future.successful(Map.empty))

      val result = service.getNodeSubmissionSummary("did:plc:user1").futureValue
      result.isRight mustBe true
      val summary = result.toOption.get
      summary.totalSubmissions mustBe 0
      summary.acceptanceRate mustBe 0.0
    }

    "return error for unknown node" in {
      when(mockNodeRepo.findByDid("did:plc:unknown")).thenReturn(Future.successful(None))

      val result = service.getNodeSubmissionSummary("did:plc:unknown").futureValue
      result mustBe Left("PDS node not found")
    }
  }

  "SubmissionProvenanceService.getSubmissionsForBiosample" should {

    "return submissions for a biosample ID" in {
      when(mockSubmissionRepo.findByBiosampleId(100))
        .thenReturn(Future.successful(Seq(testSubmission)))

      val result = service.getSubmissionsForBiosample(100).futureValue
      result.size mustBe 1
    }

    "return submissions for a biosample GUID" in {
      when(mockSubmissionRepo.findByBiosampleGuid(sampleGuid))
        .thenReturn(Future.successful(Seq(testSubmission.copy(biosampleGuid = Some(sampleGuid)))))

      val result = service.getSubmissionsForBiosampleGuid(sampleGuid).futureValue
      result.size mustBe 1
    }
  }
}
