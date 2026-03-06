package services.genomics

import helpers.ServiceSpec
import models.api.SequencerLabInfo
import models.domain.genomics.*
import org.mockito.Mockito.{reset, when}
import repositories.*

import scala.concurrent.Future

class SequencerInstrumentServiceSpec extends ServiceSpec {

  val mockInstrumentRepo: SequencerInstrumentRepository = mock[SequencerInstrumentRepository]
  val mockProposalRepo: InstrumentProposalRepository = mock[InstrumentProposalRepository]
  val mockObservationRepo: InstrumentObservationRepository = mock[InstrumentObservationRepository]

  val service = new SequencerInstrumentService(
    mockInstrumentRepo, mockProposalRepo, mockObservationRepo
  )

  override def beforeEach(): Unit = {
    reset(mockInstrumentRepo, mockProposalRepo, mockObservationRepo)
  }

  val confirmedLab: SequencerLabInfo = SequencerLabInfo(
    instrumentId = "A00123",
    labName = "Dante Labs",
    isD2c = true,
    manufacturer = Some("Illumina"),
    model = Some("NovaSeq 6000"),
    websiteUrl = Some("https://dantelabs.com")
  )

  val pendingProposal: InstrumentAssociationProposal = InstrumentAssociationProposal(
    id = Some(5),
    instrumentId = "A00123",
    proposedLabName = "Nebula Genomics",
    proposedManufacturer = Some("Illumina"),
    proposedModel = Some("NovaSeq X"),
    observationCount = 7,
    distinctCitizenCount = 4,
    confidenceScore = 0.85,
    status = ProposalStatus.ReadyForReview
  )

  def makeObservation(instrumentId: String = "A00123"): InstrumentObservation =
    InstrumentObservation(
      id = Some(1),
      atUri = s"at://did:plc:abc/us.decoding.instrument.observation/${System.nanoTime()}",
      instrumentId = instrumentId,
      labName = "Dante Labs",
      biosampleRef = "at://did:plc:abc/us.decoding.biosample/1"
    )

  "SequencerInstrumentService.lookupLab" should {

    "return confirmed lab with confidence metadata" in {
      val observations = (1 to 3).map(_ => makeObservation())
      when(mockInstrumentRepo.findLabByInstrumentId("A00123"))
        .thenReturn(Future.successful(Some(confirmedLab)))
      when(mockProposalRepo.findActiveByInstrumentId("A00123"))
        .thenReturn(Future.successful(None))
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(observations))

      whenReady(service.lookupLab("A00123")) { result =>
        result mustBe defined
        val resp = result.get
        resp.labName mustBe Some("Dante Labs")
        resp.source mustBe "CURATOR"
        resp.confidenceScore mustBe 1.0
        resp.observationCount mustBe 3
        resp.pendingProposal mustBe None
        resp.isD2c mustBe Some(true)
        resp.websiteUrl mustBe Some("https://dantelabs.com")
      }
    }

    "return confirmed lab with pending proposal when proposal differs" in {
      when(mockInstrumentRepo.findLabByInstrumentId("A00123"))
        .thenReturn(Future.successful(Some(confirmedLab)))
      when(mockProposalRepo.findActiveByInstrumentId("A00123"))
        .thenReturn(Future.successful(Some(pendingProposal)))
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(Seq(makeObservation())))

      whenReady(service.lookupLab("A00123")) { result =>
        result mustBe defined
        val resp = result.get
        resp.labName mustBe Some("Dante Labs")
        resp.source mustBe "CURATOR"
        resp.pendingProposal mustBe defined
        resp.pendingProposal.get.proposedLabName mustBe "Nebula Genomics"
        resp.pendingProposal.get.confidenceScore mustBe 0.85
      }
    }

    "not include pending proposal when proposal matches confirmed lab" in {
      val matchingProposal = pendingProposal.copy(proposedLabName = "Dante Labs")
      when(mockInstrumentRepo.findLabByInstrumentId("A00123"))
        .thenReturn(Future.successful(Some(confirmedLab)))
      when(mockProposalRepo.findActiveByInstrumentId("A00123"))
        .thenReturn(Future.successful(Some(matchingProposal)))
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(Seq(makeObservation())))

      whenReady(service.lookupLab("A00123")) { result =>
        result mustBe defined
        result.get.pendingProposal mustBe None
      }
    }

    "return consensus result from proposal when no confirmed lab" in {
      when(mockInstrumentRepo.findLabByInstrumentId("B00456"))
        .thenReturn(Future.successful(None))
      when(mockProposalRepo.findActiveByInstrumentId("B00456"))
        .thenReturn(Future.successful(Some(pendingProposal.copy(instrumentId = "B00456"))))
      when(mockObservationRepo.findByInstrumentId("B00456"))
        .thenReturn(Future.successful(Seq.empty))

      whenReady(service.lookupLab("B00456")) { result =>
        result mustBe defined
        val resp = result.get
        resp.labName mustBe Some("Nebula Genomics")
        resp.source mustBe "CONSENSUS"
        resp.confidenceScore mustBe 0.85
        resp.pendingProposal mustBe defined
        resp.pendingProposal.get.status mustBe "READY_FOR_REVIEW"
      }
    }

    "return None when no confirmed lab and no proposal" in {
      when(mockInstrumentRepo.findLabByInstrumentId("UNKNOWN"))
        .thenReturn(Future.successful(None))
      when(mockProposalRepo.findActiveByInstrumentId("UNKNOWN"))
        .thenReturn(Future.successful(None))
      when(mockObservationRepo.findByInstrumentId("UNKNOWN"))
        .thenReturn(Future.successful(Seq.empty))

      whenReady(service.lookupLab("UNKNOWN")) { result =>
        result mustBe None
      }
    }

    "include observation count from confirmed observations" in {
      val observations = (1 to 15).map(_ => makeObservation())
      when(mockInstrumentRepo.findLabByInstrumentId("A00123"))
        .thenReturn(Future.successful(Some(confirmedLab)))
      when(mockProposalRepo.findActiveByInstrumentId("A00123"))
        .thenReturn(Future.successful(None))
      when(mockObservationRepo.findByInstrumentId("A00123"))
        .thenReturn(Future.successful(observations))

      whenReady(service.lookupLab("A00123")) { result =>
        result mustBe defined
        result.get.observationCount mustBe 15
      }
    }
  }

  "SequencerInstrumentService.associateLabWithInstrument" should {

    "reject empty instrument ID" in {
      whenReady(service.associateLabWithInstrument("", "Dante Labs").failed) { ex =>
        ex mustBe an[IllegalArgumentException]
        ex.getMessage must include("Instrument ID")
      }
    }

    "reject empty lab name" in {
      whenReady(service.associateLabWithInstrument("A00123", "").failed) { ex =>
        ex mustBe an[IllegalArgumentException]
        ex.getMessage must include("Lab name")
      }
    }
  }
}
