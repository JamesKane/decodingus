package services.firehose

import helpers.ServiceSpec
import models.atmosphere.{InstrumentObservationRecord, RecordMeta}
import models.domain.genomics.{InstrumentObservation, ObservationConfidence}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.*
import services.TestTypeService

import java.time.{Instant, LocalDateTime}
import java.util.UUID
import scala.concurrent.Future

class InstrumentObservationHandlerSpec extends ServiceSpec {

  val mockCitizenBiosampleRepo: CitizenBiosampleRepository = mock[CitizenBiosampleRepository]
  val mockSeqLibraryRepo: SequenceLibraryRepository = mock[SequenceLibraryRepository]
  val mockSeqFileRepo: SequenceFileRepository = mock[SequenceFileRepository]
  val mockAlignmentRepo: AlignmentRepository = mock[AlignmentRepository]
  val mockDonorRepo: SpecimenDonorRepository = mock[SpecimenDonorRepository]
  val mockProjectRepo: ProjectRepository = mock[ProjectRepository]
  val mockTestTypeService: TestTypeService = mock[TestTypeService]
  val mockGenotypeRepo: GenotypeDataRepository = mock[GenotypeDataRepository]
  val mockPopBreakdownRepo: PopulationBreakdownRepository = mock[PopulationBreakdownRepository]
  val mockHgReconciliationRepo: HaplogroupReconciliationRepository = mock[HaplogroupReconciliationRepository]
  val mockInstrumentObsRepo: InstrumentObservationRepository = mock[InstrumentObservationRepository]
  val mockGroupProjectRepo: GroupProjectRepository = mock[GroupProjectRepository]
  val mockGroupProjectMemberRepo: GroupProjectMemberRepository = mock[GroupProjectMemberRepository]

  val mockMatchConsentRepo: MatchConsentTrackingRepository = mock[MatchConsentTrackingRepository]
  val mockMatchRequestRepo: MatchRequestTrackingRepository = mock[MatchRequestTrackingRepository]
  val mockPopAnalysisService: services.ibd.PopulationAnalysisService = mock[services.ibd.PopulationAnalysisService]

  val handler = new AtmosphereEventHandler(
    mockCitizenBiosampleRepo, mockSeqLibraryRepo, mockSeqFileRepo,
    mockAlignmentRepo, mockDonorRepo, mockProjectRepo, mockTestTypeService,
    mockGenotypeRepo, mockPopBreakdownRepo, mockHgReconciliationRepo, mockInstrumentObsRepo,
    mockGroupProjectRepo, mockGroupProjectMemberRepo,
    mockMatchConsentRepo, mockMatchRequestRepo, mockPopAnalysisService
  )

  override def beforeEach(): Unit = {
    reset(mockInstrumentObsRepo)
  }

  val testAtUri = "at://did:plc:abc123/us.decoding.instrument.observation/1"

  val testRecord: InstrumentObservationRecord = InstrumentObservationRecord(
    atUri = testAtUri,
    meta = RecordMeta(version = 1, createdAt = Instant.now(), updatedAt = Some(Instant.now()), lastModifiedField = None),
    instrumentId = "A00123",
    labName = "Dante Labs",
    biosampleRef = "at://did:plc:abc123/us.decoding.biosample/1",
    sequenceRunRef = Some("at://did:plc:abc123/us.decoding.sequenceRun/1"),
    platform = Some("ILLUMINA"),
    instrumentModel = Some("NovaSeq 6000"),
    flowcellId = Some("FC001"),
    runDate = Some(Instant.now()),
    confidence = Some("KNOWN")
  )

  def makeEvent(action: FirehoseAction, payload: Option[InstrumentObservationRecord] = Some(testRecord)): InstrumentObservationEvent =
    InstrumentObservationEvent(
      atUri = testAtUri,
      atCid = Some(UUID.randomUUID().toString),
      action = action,
      payload = payload
    )

  "AtmosphereEventHandler - InstrumentObservation" should {

    "create observation from firehose event" in {
      val event = makeEvent(FirehoseAction.Create)
      when(mockInstrumentObsRepo.findByAtUri(testAtUri)).thenReturn(Future.successful(None))
      when(mockInstrumentObsRepo.create(any[InstrumentObservation])).thenReturn(
        Future.successful(InstrumentObservation(
          id = Some(1), atUri = testAtUri, instrumentId = "A00123", labName = "Dante Labs",
          biosampleRef = "at://did:plc:abc123/us.decoding.biosample/1"
        ))
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        result.asInstanceOf[FirehoseResult.Success].message must include("created")
        verify(mockInstrumentObsRepo).create(any[InstrumentObservation])
      }
    }

    "return Conflict when observation already exists on create" in {
      val event = makeEvent(FirehoseAction.Create)
      val existing = InstrumentObservation(
        id = Some(1), atUri = testAtUri, instrumentId = "A00123", labName = "Dante Labs",
        biosampleRef = "at://did:plc:abc123/us.decoding.biosample/1"
      )
      when(mockInstrumentObsRepo.findByAtUri(testAtUri)).thenReturn(Future.successful(Some(existing)))

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Conflict]
        verify(mockInstrumentObsRepo, never()).create(any[InstrumentObservation])
      }
    }

    "return ValidationError when create has no payload" in {
      val event = makeEvent(FirehoseAction.Create, payload = None)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.ValidationError]
      }
    }

    "update existing observation" in {
      val event = makeEvent(FirehoseAction.Update)
      val existing = InstrumentObservation(
        id = Some(1), atUri = testAtUri, instrumentId = "A00123", labName = "Old Lab",
        biosampleRef = "at://did:plc:abc123/us.decoding.biosample/1"
      )
      when(mockInstrumentObsRepo.findByAtUri(testAtUri)).thenReturn(Future.successful(Some(existing)))
      when(mockInstrumentObsRepo.update(any[InstrumentObservation])).thenReturn(Future.successful(true))

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        result.asInstanceOf[FirehoseResult.Success].message must include("updated")
        verify(mockInstrumentObsRepo).update(any[InstrumentObservation])
      }
    }

    "return NotFound when updating nonexistent observation" in {
      val event = makeEvent(FirehoseAction.Update)
      when(mockInstrumentObsRepo.findByAtUri(testAtUri)).thenReturn(Future.successful(None))

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.NotFound]
      }
    }

    "return ValidationError when update has no payload" in {
      val event = makeEvent(FirehoseAction.Update, payload = None)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.ValidationError]
      }
    }

    "delete existing observation" in {
      val event = makeEvent(FirehoseAction.Delete, payload = None)
      when(mockInstrumentObsRepo.deleteByAtUri(testAtUri)).thenReturn(Future.successful(true))

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        result.asInstanceOf[FirehoseResult.Success].message must include("deleted")
      }
    }

    "return NotFound when deleting nonexistent observation" in {
      val event = makeEvent(FirehoseAction.Delete, payload = None)
      when(mockInstrumentObsRepo.deleteByAtUri(testAtUri)).thenReturn(Future.successful(false))

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.NotFound]
      }
    }

    "map confidence field correctly" in {
      val event = makeEvent(FirehoseAction.Create, payload = Some(testRecord.copy(confidence = Some("GUESSED"))))
      when(mockInstrumentObsRepo.findByAtUri(testAtUri)).thenReturn(Future.successful(None))
      when(mockInstrumentObsRepo.create(any[InstrumentObservation])).thenAnswer { invocation =>
        val obs = invocation.getArgument[InstrumentObservation](0)
        obs.confidence mustBe ObservationConfidence.Guessed
        Future.successful(obs.copy(id = Some(1)))
      }

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
      }
    }

    "default confidence to INFERRED when not specified" in {
      val event = makeEvent(FirehoseAction.Create, payload = Some(testRecord.copy(confidence = None)))
      when(mockInstrumentObsRepo.findByAtUri(testAtUri)).thenReturn(Future.successful(None))
      when(mockInstrumentObsRepo.create(any[InstrumentObservation])).thenAnswer { invocation =>
        val obs = invocation.getArgument[InstrumentObservation](0)
        obs.confidence mustBe ObservationConfidence.Inferred
        Future.successful(obs.copy(id = Some(1)))
      }

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
      }
    }
  }
}
