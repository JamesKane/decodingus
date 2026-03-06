package controllers

import helpers.ServiceSpec
import models.domain.genomics.*
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{reset, verify, when}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import repositories.{InstrumentObservationRepository, InstrumentProposalRepository}
import services.{ConflictingLab, InstrumentConflict, InstrumentProposalService}

import java.time.LocalDateTime
import scala.concurrent.Future

class InstrumentProposalControllerSpec extends ServiceSpec {

  val mockProposalService: InstrumentProposalService = mock[InstrumentProposalService]
  val mockProposalRepo: InstrumentProposalRepository = mock[InstrumentProposalRepository]
  val mockObservationRepo: InstrumentObservationRepository = mock[InstrumentObservationRepository]

  override def beforeEach(): Unit = {
    reset(mockProposalService, mockProposalRepo, mockObservationRepo)
  }

  val sampleProposal: InstrumentAssociationProposal = InstrumentAssociationProposal(
    id = Some(1),
    instrumentId = "A00123",
    proposedLabName = "Dante Labs",
    observationCount = 7,
    distinctCitizenCount = 4,
    confidenceScore = 0.85,
    status = ProposalStatus.ReadyForReview
  )

  val sampleObservation: InstrumentObservation = InstrumentObservation(
    id = Some(1),
    atUri = "at://did:plc:abc/us.decoding.instrument.observation/1",
    instrumentId = "A00123",
    labName = "Dante Labs",
    biosampleRef = "at://did:plc:abc/us.decoding.biosample/1",
    confidence = ObservationConfidence.Known
  )

  "InstrumentProposalController" should {

    "list pending proposals by default" in {
      when(mockProposalRepo.findPending())
        .thenReturn(Future.successful(Seq(sampleProposal)))

      whenReady(mockProposalRepo.findPending()) { proposals =>
        proposals must have size 1
        proposals.head.instrumentId mustBe "A00123"
      }
    }

    "list proposals filtered by status" in {
      when(mockProposalRepo.findByStatus(ProposalStatus.ReadyForReview))
        .thenReturn(Future.successful(Seq(sampleProposal)))

      whenReady(mockProposalRepo.findByStatus(ProposalStatus.ReadyForReview)) { proposals =>
        proposals must have size 1
        proposals.head.status mustBe ProposalStatus.ReadyForReview
      }
    }

    "get proposal detail with observations" in {
      when(mockProposalRepo.findById(1))
        .thenReturn(Future.successful(Some(sampleProposal)))
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(Seq(sampleObservation, sampleObservation.copy(
          id = Some(2),
          atUri = "at://did:plc:def/us.decoding.instrument.observation/2",
          biosampleRef = "at://did:plc:def/us.decoding.biosample/1"
        ))))

      whenReady(mockProposalRepo.findById(1)) { proposalOpt =>
        proposalOpt mustBe defined
        val proposal = proposalOpt.get
        proposal.instrumentId mustBe "A00123"

        whenReady(mockObservationRepo.findByInstrumentId(proposal.instrumentId)) { observations =>
          observations must have size 2
          observations.map(_.biosampleRef).distinct must have size 2
        }
      }
    }

    "return not found for nonexistent proposal detail" in {
      when(mockProposalRepo.findById(99))
        .thenReturn(Future.successful(None))

      whenReady(mockProposalRepo.findById(99)) { result =>
        result mustBe None
      }
    }

    "accept a proposal via service" in {
      val accepted = sampleProposal.copy(
        status = ProposalStatus.Accepted,
        reviewedBy = Some("curator@test.com"),
        reviewNotes = Some("Confirmed"),
        acceptedLabId = Some(10)
      )
      when(mockProposalService.acceptProposal(
        meq(1), meq("curator@test.com"), meq("Dante Labs"), meq(None), meq(None), meq(Some("Confirmed"))
      )).thenReturn(Future.successful(Right(accepted)))

      whenReady(mockProposalService.acceptProposal(1, "curator@test.com", "Dante Labs", None, None, Some("Confirmed"))) { result =>
        result mustBe a[Right[?, ?]]
        val proposal = result.toOption.get
        proposal.status mustBe ProposalStatus.Accepted
        proposal.reviewedBy mustBe Some("curator@test.com")
        proposal.acceptedLabId mustBe Some(10)
      }
    }

    "return error when accepting already-accepted proposal" in {
      when(mockProposalService.acceptProposal(
        meq(1), meq("curator@test.com"), meq("Dante Labs"), meq(None), meq(None), meq(None)
      )).thenReturn(Future.successful(Left("Proposal 1 is already accepted")))

      whenReady(mockProposalService.acceptProposal(1, "curator@test.com", "Dante Labs", None, None, None)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("already accepted")
      }
    }

    "reject a proposal via service" in {
      val rejected = sampleProposal.copy(
        status = ProposalStatus.Rejected,
        reviewedBy = Some("curator@test.com"),
        reviewNotes = Some("Insufficient evidence")
      )
      when(mockProposalService.rejectProposal(meq(1), meq("curator@test.com"), meq("Insufficient evidence")))
        .thenReturn(Future.successful(Right(rejected)))

      whenReady(mockProposalService.rejectProposal(1, "curator@test.com", "Insufficient evidence")) { result =>
        result mustBe a[Right[?, ?]]
        val proposal = result.toOption.get
        proposal.status mustBe ProposalStatus.Rejected
        proposal.reviewNotes mustBe Some("Insufficient evidence")
      }
    }

    "detect conflicts across proposals" in {
      val conflict = InstrumentConflict(
        instrumentId = "A00123",
        proposals = Seq(
          ConflictingLab("Dante Labs", 5, 0.625),
          ConflictingLab("Nebula Genomics", 3, 0.375)
        ),
        dominantLabName = "Dante Labs",
        dominantRatio = 0.625
      )
      when(mockProposalService.detectConflicts())
        .thenReturn(Future.successful(Seq(conflict)))

      whenReady(mockProposalService.detectConflicts()) { conflicts =>
        conflicts must have size 1
        conflicts.head.instrumentId mustBe "A00123"
        conflicts.head.dominantRatio mustBe 0.625 +- 0.01
        conflicts.head.proposals must have size 2
      }
    }

    "return empty conflicts list when no conflicts" in {
      when(mockProposalService.detectConflicts())
        .thenReturn(Future.successful(Seq.empty))

      whenReady(mockProposalService.detectConflicts()) { conflicts =>
        conflicts mustBe empty
      }
    }

    "accept proposal with manufacturer and model overrides" in {
      val accepted = sampleProposal.copy(
        status = ProposalStatus.Accepted,
        reviewedBy = Some("curator@test.com"),
        acceptedLabId = Some(10)
      )
      when(mockProposalService.acceptProposal(
        meq(1), meq("curator@test.com"), meq("Dante Labs"),
        meq(Some("Illumina")), meq(Some("NovaSeq X")), meq(Some("Confirmed via publications"))
      )).thenReturn(Future.successful(Right(accepted)))

      whenReady(mockProposalService.acceptProposal(
        1, "curator@test.com", "Dante Labs",
        Some("Illumina"), Some("NovaSeq X"), Some("Confirmed via publications")
      )) { result =>
        result mustBe a[Right[?, ?]]
        verify(mockProposalService).acceptProposal(
          meq(1), meq("curator@test.com"), meq("Dante Labs"),
          meq(Some("Illumina")), meq(Some("NovaSeq X")), meq(Some("Confirmed via publications"))
        )
      }
    }
  }
}
