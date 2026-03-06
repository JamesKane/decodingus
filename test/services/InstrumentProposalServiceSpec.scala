package services

import helpers.ServiceSpec
import models.api.genomics.AssociateLabWithInstrumentResponse
import models.domain.genomics.*
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.*

import java.time.LocalDateTime
import scala.concurrent.Future

class InstrumentProposalServiceSpec extends ServiceSpec {

  val mockObservationRepo: InstrumentObservationRepository = mock[InstrumentObservationRepository]
  val mockProposalRepo: InstrumentProposalRepository = mock[InstrumentProposalRepository]
  val mockInstrumentRepo: SequencerInstrumentRepository = mock[SequencerInstrumentRepository]
  val mockLabRepo: SequencingLabRepository = mock[SequencingLabRepository]

  val service = new InstrumentProposalService(
    mockObservationRepo, mockProposalRepo, mockInstrumentRepo, mockLabRepo
  )

  override def beforeEach(): Unit = {
    reset(mockObservationRepo, mockProposalRepo, mockInstrumentRepo, mockLabRepo)
  }

  def makeObservation(
                       instrumentId: String = "A00123",
                       labName: String = "Dante Labs",
                       biosampleRef: String = s"at://did:plc:citizen1/us.decoding.biosample/1",
                       confidence: ObservationConfidence = ObservationConfidence.Known,
                       createdAt: LocalDateTime = LocalDateTime.now()
                     ): InstrumentObservation =
    InstrumentObservation(
      id = Some(1),
      atUri = s"at://did:plc:citizen1/us.decoding.instrument.observation/${System.nanoTime()}",
      instrumentId = instrumentId,
      labName = labName,
      biosampleRef = biosampleRef,
      confidence = confidence,
      createdAt = createdAt
    )

  "InstrumentProposalService" should {

    "return None when fewer than min observations" in {
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(Seq(makeObservation())))

      whenReady(service.aggregateObservations("A00123")) { result =>
        result mustBe None
      }
    }

    "aggregate observations for a single lab" in {
      val obs = (1 to 5).map(i =>
        makeObservation(biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(obs))

      whenReady(service.aggregateObservations("A00123")) { result =>
        result mustBe defined
        val agg = result.get
        agg.dominantLabName mustBe "Dante Labs"
        agg.observationCount mustBe 5
        agg.distinctCitizenCount mustBe 5
        agg.conflict mustBe None
      }
    }

    "detect conflicts when multiple labs claim same instrument" in {
      val danteObs = (1 to 3).map(i =>
        makeObservation(labName = "Dante Labs", biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )
      val nebulaObs = (4 to 5).map(i =>
        makeObservation(labName = "Nebula Genomics", biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(danteObs ++ nebulaObs))

      whenReady(service.aggregateObservations("A00123")) { result =>
        result mustBe defined
        val agg = result.get
        agg.dominantLabName mustBe "Dante Labs"
        agg.conflict mustBe defined
        agg.conflict.get.dominantRatio mustBe 0.6 +- 0.01
        agg.conflict.get.proposals must have size 2
      }
    }

    "calculate confidence score correctly" in {
      val obs = (1 to 10).map(i =>
        makeObservation(
          biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1",
          confidence = ObservationConfidence.Known,
          createdAt = LocalDateTime.now()
        )
      )

      val result = service.buildAggregation("A00123", obs)

      // 10 obs / 10 threshold = 1.0 * 0.4 = 0.4
      // 10 citizens / 3 min = capped 1.0 * 0.3 = 0.3
      // Recent = 1.0 * 0.2 = 0.2
      // Known = 1.0 * 0.1 = 0.1
      // Total = 1.0
      result.confidenceScore mustBe 1.0 +- 0.01
    }

    "weight GUESSED confidence lower" in {
      val obs = (1 to 4).map(i =>
        makeObservation(
          biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1",
          confidence = ObservationConfidence.Guessed,
          createdAt = LocalDateTime.now()
        )
      )

      val result = service.buildAggregation("A00123", obs)

      // 4/10 * 0.4 = 0.16
      // 4/3 capped 1.0 * 0.3 = 0.3
      // Recent 1.0 * 0.2 = 0.2
      // Guessed 0.3 * 0.1 = 0.03
      // Total = 0.69
      result.confidenceScore mustBe 0.69 +- 0.01
    }

    "calculate recency score as 1.0 for recent observations" in {
      val recentObs = Seq(makeObservation(createdAt = LocalDateTime.now()))
      val score = service.calculateRecencyScore(recentObs)
      score mustBe 1.0
    }

    "calculate recency score as decayed for old observations" in {
      val oldObs = Seq(makeObservation(createdAt = LocalDateTime.now().minusDays(60)))
      val score = service.calculateRecencyScore(oldObs)
      score must be < 1.0
      score must be > 0.0
    }

    "calculate average confidence level" in {
      val obs = Seq(
        makeObservation(confidence = ObservationConfidence.Known),
        makeObservation(confidence = ObservationConfidence.Inferred),
        makeObservation(confidence = ObservationConfidence.Guessed)
      )
      val avg = service.calculateAvgConfidenceLevel(obs)
      // (1.0 + 0.7 + 0.3) / 3 = 0.667
      avg mustBe 0.667 +- 0.01
    }

    "create new proposal when none exists" in {
      val obs = (1 to 3).map(i =>
        makeObservation(biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(obs))
      when(mockProposalRepo.findActiveByInstrumentId("A00123"))
        .thenReturn(Future.successful(None))
      when(mockProposalRepo.create(any[InstrumentAssociationProposal]))
        .thenAnswer { invocation =>
          val p = invocation.getArgument[InstrumentAssociationProposal](0)
          Future.successful(p.copy(id = Some(1)))
        }

      whenReady(service.createOrUpdateProposal("A00123")) { result =>
        result mustBe defined
        result.get.proposedLabName mustBe "Dante Labs"
        result.get.status mustBe ProposalStatus.Pending
        verify(mockProposalRepo).create(any[InstrumentAssociationProposal])
      }
    }

    "create proposal with READY_FOR_REVIEW when threshold met" in {
      val obs = (1 to 5).map(i =>
        makeObservation(biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(obs))
      when(mockProposalRepo.findActiveByInstrumentId("A00123"))
        .thenReturn(Future.successful(None))
      when(mockProposalRepo.create(any[InstrumentAssociationProposal]))
        .thenAnswer { invocation =>
          val p = invocation.getArgument[InstrumentAssociationProposal](0)
          Future.successful(p.copy(id = Some(1)))
        }

      whenReady(service.createOrUpdateProposal("A00123")) { result =>
        result mustBe defined
        result.get.status mustBe ProposalStatus.ReadyForReview
      }
    }

    "update existing proposal when one exists" in {
      val obs = (1 to 4).map(i =>
        makeObservation(biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )
      val existingProposal = InstrumentAssociationProposal(
        id = Some(1), instrumentId = "A00123", proposedLabName = "Dante Labs",
        observationCount = 2, distinctCitizenCount = 2, status = ProposalStatus.Pending
      )
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(obs))
      when(mockProposalRepo.findActiveByInstrumentId("A00123"))
        .thenReturn(Future.successful(Some(existingProposal)))
      when(mockProposalRepo.update(any[InstrumentAssociationProposal]))
        .thenReturn(Future.successful(true))

      whenReady(service.createOrUpdateProposal("A00123")) { result =>
        result mustBe defined
        result.get.observationCount mustBe 4
        verify(mockProposalRepo).update(any[InstrumentAssociationProposal])
        verify(mockProposalRepo, never()).create(any[InstrumentAssociationProposal])
      }
    }

    "not change status of UNDER_REVIEW proposals" in {
      val result = service.evaluateThreshold(
        AggregationResult("A00123", "Dante Labs", 10, 5, 0.9, None, None, None, None, None),
        ProposalStatus.UnderReview
      )
      result mustBe ProposalStatus.UnderReview
    }

    "accept a proposal and create lab association" in {
      val proposal = InstrumentAssociationProposal(
        id = Some(1), instrumentId = "A00123", proposedLabName = "Dante Labs",
        status = ProposalStatus.ReadyForReview
      )
      when(mockProposalRepo.findById(1)).thenReturn(Future.successful(Some(proposal)))
      when(mockInstrumentRepo.associateLabWithInstrument("A00123", "Dante Labs", None, None))
        .thenReturn(Future.successful(AssociateLabWithInstrumentResponse(
          instrumentId = "A00123", labId = 10, labName = "Dante Labs",
          manufacturer = None, model = None, isNewLab = false, message = "ok"
        )))
      when(mockProposalRepo.update(any[InstrumentAssociationProposal]))
        .thenReturn(Future.successful(true))

      whenReady(service.acceptProposal(1, "curator@test.com", "Dante Labs", None, None, Some("Confirmed"))) { result =>
        result mustBe a[Right[?, ?]]
        val accepted = result.toOption.get
        accepted.status mustBe ProposalStatus.Accepted
        accepted.reviewedBy mustBe Some("curator@test.com")
        accepted.acceptedLabId mustBe Some(10)
      }
    }

    "reject accepting an already-accepted proposal" in {
      val proposal = InstrumentAssociationProposal(
        id = Some(1), instrumentId = "A00123", proposedLabName = "Dante Labs",
        status = ProposalStatus.Accepted
      )
      when(mockProposalRepo.findById(1)).thenReturn(Future.successful(Some(proposal)))

      whenReady(service.acceptProposal(1, "curator@test.com", "Dante Labs", None, None, None)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("already accepted")
      }
    }

    "reject a proposal" in {
      val proposal = InstrumentAssociationProposal(
        id = Some(1), instrumentId = "A00123", proposedLabName = "Dante Labs",
        status = ProposalStatus.ReadyForReview
      )
      when(mockProposalRepo.findById(1)).thenReturn(Future.successful(Some(proposal)))
      when(mockProposalRepo.update(any[InstrumentAssociationProposal]))
        .thenReturn(Future.successful(true))

      whenReady(service.rejectProposal(1, "curator@test.com", "Insufficient evidence")) { result =>
        result mustBe a[Right[?, ?]]
        val rejected = result.toOption.get
        rejected.status mustBe ProposalStatus.Rejected
        rejected.reviewNotes mustBe Some("Insufficient evidence")
      }
    }

    "return error when rejecting nonexistent proposal" in {
      when(mockProposalRepo.findById(99)).thenReturn(Future.successful(None))

      whenReady(service.rejectProposal(99, "curator@test.com", "No reason")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("not found")
      }
    }

    "detect conflicts across pending proposals" in {
      val proposal = InstrumentAssociationProposal(
        id = Some(1), instrumentId = "A00123", proposedLabName = "Dante Labs",
        status = ProposalStatus.Pending
      )
      val danteObs = (1 to 3).map(i =>
        makeObservation(labName = "Dante Labs", biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )
      val nebulaObs = (4 to 5).map(i =>
        makeObservation(labName = "Nebula Genomics", biosampleRef = s"at://did:plc:citizen$i/us.decoding.biosample/1")
      )

      when(mockProposalRepo.findPending()).thenReturn(Future.successful(Seq(proposal)))
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(danteObs ++ nebulaObs))

      whenReady(service.detectConflicts()) { conflicts =>
        conflicts must have size 1
        conflicts.head.instrumentId mustBe "A00123"
        conflicts.head.dominantLabName mustBe "Dante Labs"
        conflicts.head.dominantRatio mustBe 0.6 +- 0.01
      }
    }
  }
}
