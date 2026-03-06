package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.domain.discovery.*
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito.{never, reset, verify, when}
import play.api.libs.json.Json
import repositories.{CuratorActionRepository, ProposedBranchRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class DiscoveryProposalServiceSpec extends ServiceSpec {

  val mockProposedBranchRepo: ProposedBranchRepository = mock[ProposedBranchRepository]
  val mockCuratorActionRepo: CuratorActionRepository = mock[CuratorActionRepository]

  val service = new DiscoveryProposalService(mockProposedBranchRepo, mockCuratorActionRepo)

  override def beforeEach(): Unit = {
    reset(mockProposedBranchRepo, mockCuratorActionRepo)
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 6, 1, 12, 0)
  val curatorId = "curator@decodingus.org"

  def makeProposal(
    id: Int,
    status: ProposedBranchStatus = ProposedBranchStatus.Pending,
    consensusCount: Int = 1
  ): ProposedBranch =
    ProposedBranch(
      id = Some(id),
      parentHaplogroupId = 100,
      haplogroupType = HaplogroupType.Y,
      status = status,
      consensusCount = consensusCount,
      createdAt = now,
      updatedAt = now
    )

  "DiscoveryProposalService" should {

    "list proposals by status" in {
      val proposals = Seq(makeProposal(1, ProposedBranchStatus.ReadyForReview))
      when(mockProposedBranchRepo.findByStatus(ProposedBranchStatus.ReadyForReview, Some(HaplogroupType.Y)))
        .thenReturn(Future.successful(proposals))

      whenReady(service.listProposals(Some(HaplogroupType.Y), Some(ProposedBranchStatus.ReadyForReview))) { result =>
        result must have size 1
        result.head.id mustBe Some(1)
      }
    }

    "list all active proposals when no status filter" in {
      when(mockProposedBranchRepo.findByStatus(ProposedBranchStatus.Pending, None))
        .thenReturn(Future.successful(Seq(makeProposal(1))))
      when(mockProposedBranchRepo.findByStatus(ProposedBranchStatus.ReadyForReview, None))
        .thenReturn(Future.successful(Seq(makeProposal(2, ProposedBranchStatus.ReadyForReview))))
      when(mockProposedBranchRepo.findByStatus(ProposedBranchStatus.UnderReview, None))
        .thenReturn(Future.successful(Seq.empty))
      when(mockProposedBranchRepo.findByStatus(ProposedBranchStatus.Accepted, None))
        .thenReturn(Future.successful(Seq.empty))

      whenReady(service.listProposals(None, None)) { result =>
        result must have size 2
      }
    }

    "get proposal details with variants, evidence, and audit trail" in {
      val proposal = makeProposal(10, ProposedBranchStatus.ReadyForReview, 3)
      val variants = Seq(ProposedBranchVariant(Some(1), 10, 42, true, 3, now, now))
      val evidence = Seq(ProposedBranchEvidence(Some(1), 10, BiosampleSourceType.External, 1, UUID.randomUUID()))
      val actions = Seq(CuratorAction(Some(1), curatorId, CuratorActionType.Review, CuratorTargetType.ProposedBranch, 10))

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))
      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(variants))
      when(mockProposedBranchRepo.getEvidence(10)).thenReturn(Future.successful(evidence))
      when(mockCuratorActionRepo.findByTarget(CuratorTargetType.ProposedBranch, 10))
        .thenReturn(Future.successful(actions))

      whenReady(service.getProposalDetails(10)) { result =>
        result mustBe defined
        val details = result.get
        details.proposal.id mustBe Some(10)
        details.variants must have size 1
        details.evidence must have size 1
        details.auditTrail must have size 1
      }
    }

    "return None for nonexistent proposal details" in {
      when(mockProposedBranchRepo.findById(999)).thenReturn(Future.successful(None))

      whenReady(service.getProposalDetails(999)) { result =>
        result mustBe empty
      }
    }

    "accept a proposal under review" in {
      val proposal = makeProposal(10, ProposedBranchStatus.UnderReview, 5)

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))
      when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))
      when(mockCuratorActionRepo.create(any[CuratorAction])).thenAnswer { invocation =>
        val a = invocation.getArgument[CuratorAction](0)
        Future.successful(a.copy(id = Some(1)))
      }

      whenReady(service.acceptProposal(10, curatorId, "R-Z1234", Some("Strong evidence"))) { result =>
        result.status mustBe ProposedBranchStatus.Accepted
        result.proposedName mustBe Some("R-Z1234")
        result.reviewedBy mustBe Some(curatorId)
        verify(mockCuratorActionRepo).create(any[CuratorAction])
      }
    }

    "reject accepting a proposal in Pending status" in {
      val proposal = makeProposal(10, ProposedBranchStatus.Pending)

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))

      whenReady(service.acceptProposal(10, curatorId, "R-Z1234", None).failed) { ex =>
        ex mustBe a[IllegalStateException]
        ex.getMessage must include("Cannot transition")
        verify(mockProposedBranchRepo, never()).update(any[ProposedBranch])
      }
    }

    "reject a proposal" in {
      val proposal = makeProposal(10, ProposedBranchStatus.UnderReview, 3)

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))
      when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))
      when(mockCuratorActionRepo.create(any[CuratorAction])).thenAnswer { invocation =>
        val a = invocation.getArgument[CuratorAction](0)
        Future.successful(a.copy(id = Some(1)))
      }

      whenReady(service.rejectProposal(10, curatorId, "Insufficient evidence")) { result =>
        result.status mustBe ProposedBranchStatus.Rejected
        result.notes mustBe Some("Insufficient evidence")
        result.reviewedBy mustBe Some(curatorId)
        verify(mockCuratorActionRepo).create(any[CuratorAction])
      }
    }

    "reject a proposal from any reviewable status" in {
      // Can reject from Pending, ReadyForReview, UnderReview, or Accepted
      for (status <- Seq(ProposedBranchStatus.Pending, ProposedBranchStatus.ReadyForReview,
        ProposedBranchStatus.UnderReview, ProposedBranchStatus.Accepted)) {
        reset(mockProposedBranchRepo, mockCuratorActionRepo)
        val proposal = makeProposal(10, status)

        when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))
        when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))
        when(mockCuratorActionRepo.create(any[CuratorAction])).thenAnswer { invocation =>
          val a = invocation.getArgument[CuratorAction](0)
          Future.successful(a.copy(id = Some(1)))
        }

        whenReady(service.rejectProposal(10, curatorId, "Rejected")) { result =>
          result.status mustBe ProposedBranchStatus.Rejected
        }
      }
    }

    "fail to reject an already promoted proposal" in {
      val proposal = makeProposal(10, ProposedBranchStatus.Promoted)

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))

      whenReady(service.rejectProposal(10, curatorId, "Too late").failed) { ex =>
        ex mustBe a[IllegalStateException]
        ex.getMessage must include("Cannot transition")
      }
    }

    "start review of a ReadyForReview proposal" in {
      val proposal = makeProposal(10, ProposedBranchStatus.ReadyForReview, 3)

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))
      when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))
      when(mockCuratorActionRepo.create(any[CuratorAction])).thenAnswer { invocation =>
        val a = invocation.getArgument[CuratorAction](0)
        Future.successful(a.copy(id = Some(1)))
      }

      whenReady(service.startReview(10, curatorId)) { result =>
        result.status mustBe ProposedBranchStatus.UnderReview
        result.reviewedBy mustBe Some(curatorId)
      }
    }

    "fail to start review of a Pending proposal" in {
      val proposal = makeProposal(10, ProposedBranchStatus.Pending)

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))

      whenReady(service.startReview(10, curatorId).failed) { ex =>
        ex mustBe a[IllegalStateException]
      }
    }

    "fail when proposal not found" in {
      when(mockProposedBranchRepo.findById(999)).thenReturn(Future.successful(None))

      whenReady(service.acceptProposal(999, curatorId, "R-X1", None).failed) { ex =>
        ex mustBe a[NoSuchElementException]
        ex.getMessage must include("not found")
      }
    }

    "get audit trail" in {
      val actions = Seq(
        CuratorAction(Some(1), curatorId, CuratorActionType.Review, CuratorTargetType.ProposedBranch, 10),
        CuratorAction(Some(2), curatorId, CuratorActionType.Accept, CuratorTargetType.ProposedBranch, 10)
      )
      when(mockCuratorActionRepo.findByTarget(CuratorTargetType.ProposedBranch, 10))
        .thenReturn(Future.successful(actions))

      whenReady(service.getAuditTrail(10)) { result =>
        result must have size 2
      }
    }
  }

  "validateStatusTransition" should {
    "allow Pending -> ReadyForReview" in {
      noException should be thrownBy {
        service.validateStatusTransition(ProposedBranchStatus.Pending, ProposedBranchStatus.ReadyForReview)
      }
    }

    "allow ReadyForReview -> UnderReview" in {
      noException should be thrownBy {
        service.validateStatusTransition(ProposedBranchStatus.ReadyForReview, ProposedBranchStatus.UnderReview)
      }
    }

    "allow UnderReview -> Accepted" in {
      noException should be thrownBy {
        service.validateStatusTransition(ProposedBranchStatus.UnderReview, ProposedBranchStatus.Accepted)
      }
    }

    "allow Accepted -> Promoted" in {
      noException should be thrownBy {
        service.validateStatusTransition(ProposedBranchStatus.Accepted, ProposedBranchStatus.Promoted)
      }
    }

    "reject Pending -> Accepted" in {
      an[IllegalStateException] should be thrownBy {
        service.validateStatusTransition(ProposedBranchStatus.Pending, ProposedBranchStatus.Accepted)
      }
    }

    "reject Promoted -> anything" in {
      an[IllegalStateException] should be thrownBy {
        service.validateStatusTransition(ProposedBranchStatus.Promoted, ProposedBranchStatus.Rejected)
      }
    }
  }
}
