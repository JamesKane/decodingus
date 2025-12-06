package services.firehose

import com.vividsolutions.jts.geom.Point
import models.api.{ExternalBiosampleRequest, SequenceDataInfo}
import models.domain.genomics.{BiologicalSex, BiosampleType, CitizenBiosample, SpecimenDonor}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import repositories._
import services.BiosampleDataService

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class CitizenBiosampleEventHandlerSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // Helper to create a minimal valid request
  def createRequest(
    atUri: String = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1",
    accession: String = "TEST-001",
    donorIdentifier: Option[String] = Some("Subject-001")
  ): ExternalBiosampleRequest = ExternalBiosampleRequest(
    sampleAccession = accession,
    sourceSystem = "test",
    description = "Test biosample",
    alias = Some("test-alias"),
    centerName = "Test Lab",
    sex = Some(BiologicalSex.Male),
    latitude = None,
    longitude = None,
    citizenDid = None,
    atUri = Some(atUri),
    donorIdentifier = donorIdentifier,
    donorType = Some(BiosampleType.Citizen),
    publication = None,
    haplogroups = None,
    sequenceData = SequenceDataInfo(
      reads = Some(1000000),
      readLength = Some(150),
      coverage = Some(30.0),
      platformName = "ILLUMINA",
      testType = "WGS",
      files = Seq.empty
    ),
    atCid = None
  )

  def createMocks(): (
    CitizenBiosampleRepository,
    BiosampleDataService,
    PublicationRepository,
    PublicationCitizenBiosampleRepository,
    CitizenBiosampleOriginalHaplogroupRepository,
    SpecimenDonorRepository
  ) = (
    mock[CitizenBiosampleRepository],
    mock[BiosampleDataService],
    mock[PublicationRepository],
    mock[PublicationCitizenBiosampleRepository],
    mock[CitizenBiosampleOriginalHaplogroupRepository],
    mock[SpecimenDonorRepository]
  )

  "CitizenBiosampleEventHandler" should {

    "create a new biosample successfully" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val request = createRequest()
      val event = CitizenBiosampleEvent.forCreate(request)

      // Mock: no existing biosample with this accession
      when(biosampleRepo.findByAccession(anyString()))
        .thenReturn(Future.successful(None))

      // Mock: no existing donor, create new one
      when(donorRepo.findByDidAndIdentifier(anyString(), anyString()))
        .thenReturn(Future.successful(None))

      when(donorRepo.create(any[SpecimenDonor]))
        .thenAnswer(new Answer[Future[SpecimenDonor]] {
          override def answer(invocation: InvocationOnMock): Future[SpecimenDonor] = {
            val donor = invocation.getArgument[SpecimenDonor](0)
            Future.successful(donor.copy(id = Some(1)))
          }
        })

      // Mock: create biosample
      when(biosampleRepo.create(any[CitizenBiosample]))
        .thenAnswer(new Answer[Future[CitizenBiosample]] {
          override def answer(invocation: InvocationOnMock): Future[CitizenBiosample] = {
            val bs = invocation.getArgument[CitizenBiosample](0)
            Future.successful(bs.copy(id = Some(100)))
          }
        })

      // Mock: sequence data handling
      when(dataService.addSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        val success = result.asInstanceOf[FirehoseResult.Success]
        success.sampleGuid mustBe defined
        success.newAtCid must not be empty

        verify(donorRepo).create(any[SpecimenDonor])
        verify(biosampleRepo).create(any[CitizenBiosample])
        verify(dataService).addSequenceData(any[UUID], any[SequenceDataInfo])
      }
    }

    "return Conflict when accession already exists" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val request = createRequest()
      val event = CitizenBiosampleEvent.forCreate(request)

      val existingBiosample = CitizenBiosample(
        id = Some(1),
        atUri = Some("at://existing"),
        accession = Some("TEST-001"),
        alias = None,
        sourcePlatform = None,
        collectionDate = None,
        sex = None,
        geocoord = None,
        description = None,
        sampleGuid = UUID.randomUUID(),
        deleted = false,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )

      when(biosampleRepo.findByAccession("TEST-001"))
        .thenReturn(Future.successful(Some(existingBiosample)))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Conflict]
        result.asInstanceOf[FirehoseResult.Conflict].message must include("already exists")

        verify(biosampleRepo, never).create(any[CitizenBiosample])
      }
    }

    "reuse existing donor when found" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val request = createRequest()
      val event = CitizenBiosampleEvent.forCreate(request)

      when(biosampleRepo.findByAccession(anyString()))
        .thenReturn(Future.successful(None))

      // Existing donor found
      val existingDonor = SpecimenDonor(
        id = Some(42),
        donorIdentifier = "Subject-001",
        originBiobank = "Test Lab",
        donorType = BiosampleType.Citizen,
        sex = None,
        geocoord = None,
        atUri = Some("did:plc:test123")
      )
      when(donorRepo.findByDidAndIdentifier("did:plc:test123", "Subject-001"))
        .thenReturn(Future.successful(Some(existingDonor)))

      when(biosampleRepo.create(any[CitizenBiosample]))
        .thenAnswer(new Answer[Future[CitizenBiosample]] {
          override def answer(invocation: InvocationOnMock): Future[CitizenBiosample] = {
            val bs = invocation.getArgument[CitizenBiosample](0)
            // Verify the donor ID was set correctly
            bs.specimenDonorId mustBe Some(42)
            Future.successful(bs.copy(id = Some(100)))
          }
        })

      when(dataService.addSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]

        // Should NOT create a new donor
        verify(donorRepo, never).create(any[SpecimenDonor])
      }
    }

    "update existing biosample successfully" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val existingGuid = UUID.randomUUID()
      val existingAtCid = "existing-cid-123"
      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1"

      val existingBiosample = CitizenBiosample(
        id = Some(1),
        atUri = Some(atUri),
        accession = Some("TEST-001"),
        alias = Some("old-alias"),
        sourcePlatform = Some("old-system"),
        collectionDate = None,
        sex = Some(BiologicalSex.Male),
        geocoord = None,
        description = Some("Old description"),
        sampleGuid = existingGuid,
        deleted = false,
        atCid = Some(existingAtCid),
        createdAt = LocalDateTime.now().minusDays(1),
        updatedAt = LocalDateTime.now().minusDays(1),
        specimenDonorId = Some(42)
      )

      // Request without donorIdentifier - should preserve existing donor
      val request = createRequest(atUri = atUri, donorIdentifier = None).copy(
        description = "Updated description",
        alias = Some("new-alias"),
        atCid = Some(existingAtCid)
      )
      val event = CitizenBiosampleEvent.forUpdate(atUri, request)

      when(biosampleRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(Some(existingBiosample)))

      when(biosampleRepo.update(any[CitizenBiosample], any[Option[String]]))
        .thenReturn(Future.successful(true))

      when(dataService.replaceSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        val success = result.asInstanceOf[FirehoseResult.Success]
        success.sampleGuid mustBe Some(existingGuid)

        verify(biosampleRepo).update(any[CitizenBiosample], any[Option[String]])
        verify(dataService).replaceSequenceData(any[UUID], any[SequenceDataInfo])
        // Should NOT touch donor repo since donorIdentifier is None
        verify(donorRepo, never).findByDidAndIdentifier(anyString(), anyString())
      }
    }

    "update biosample with new donor identifier" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val existingGuid = UUID.randomUUID()
      val existingAtCid = "existing-cid-123"
      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1"

      val existingBiosample = CitizenBiosample(
        id = Some(1),
        atUri = Some(atUri),
        accession = Some("TEST-001"),
        alias = Some("old-alias"),
        sourcePlatform = Some("old-system"),
        collectionDate = None,
        sex = Some(BiologicalSex.Male),
        geocoord = None,
        description = Some("Old description"),
        sampleGuid = existingGuid,
        deleted = false,
        atCid = Some(existingAtCid),
        createdAt = LocalDateTime.now().minusDays(1),
        updatedAt = LocalDateTime.now().minusDays(1),
        specimenDonorId = Some(42) // Old donor
      )

      // Request WITH donorIdentifier - should resolve new donor
      val request = createRequest(atUri = atUri, donorIdentifier = Some("NewSubject-002")).copy(
        atCid = Some(existingAtCid)
      )
      val event = CitizenBiosampleEvent.forUpdate(atUri, request)

      when(biosampleRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(Some(existingBiosample)))

      // New donor needs to be created
      when(donorRepo.findByDidAndIdentifier("did:plc:test123", "NewSubject-002"))
        .thenReturn(Future.successful(None))

      when(donorRepo.create(any[SpecimenDonor]))
        .thenAnswer(new Answer[Future[SpecimenDonor]] {
          override def answer(invocation: InvocationOnMock): Future[SpecimenDonor] = {
            val donor = invocation.getArgument[SpecimenDonor](0)
            Future.successful(donor.copy(id = Some(99))) // New donor ID
          }
        })

      when(biosampleRepo.update(any[CitizenBiosample], any[Option[String]]))
        .thenReturn(Future.successful(true))

      when(dataService.replaceSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(donorRepo).findByDidAndIdentifier("did:plc:test123", "NewSubject-002")
        verify(donorRepo).create(any[SpecimenDonor])
      }
    }

    "return NotFound when updating non-existent biosample" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/nonexistent"
      val request = createRequest(atUri = atUri)
      val event = CitizenBiosampleEvent.forUpdate(atUri, request)

      when(biosampleRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(None))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.NotFound]
        result.atUri mustBe atUri
      }
    }

    "return Conflict on optimistic locking failure during update" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1"
      val existingBiosample = CitizenBiosample(
        id = Some(1),
        atUri = Some(atUri),
        accession = Some("TEST-001"),
        alias = None,
        sourcePlatform = None,
        collectionDate = None,
        sex = None,
        geocoord = None,
        description = None,
        sampleGuid = UUID.randomUUID(),
        deleted = false,
        atCid = Some("current-cid"),
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )

      val request = createRequest(atUri = atUri).copy(
        atCid = Some("stale-cid") // Different from current
      )
      val event = CitizenBiosampleEvent.forUpdate(atUri, request)

      when(biosampleRepo.findByAtUri(atUri))
        .thenReturn(Future.successful(Some(existingBiosample)))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Conflict]
        result.asInstanceOf[FirehoseResult.Conflict].message must include("Optimistic locking")

        verify(biosampleRepo, never).update(any[CitizenBiosample], any[Option[String]])
      }
    }

    "delete biosample successfully" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1"
      val event = CitizenBiosampleEvent.forDelete(atUri)

      when(biosampleRepo.softDeleteByAtUri(atUri))
        .thenReturn(Future.successful(true))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(biosampleRepo).softDeleteByAtUri(atUri)
      }
    }

    "return NotFound when deleting non-existent biosample" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/nonexistent"
      val event = CitizenBiosampleEvent.forDelete(atUri)

      when(biosampleRepo.softDeleteByAtUri(atUri))
        .thenReturn(Future.successful(false))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.NotFound]
      }
    }

    "extract DID correctly from atUri" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      // Request without explicit citizenDid - should extract from atUri
      val request = createRequest(
        atUri = "at://did:plc:abc123xyz/com.decodingus.atmosphere.biosample/rkey1"
      ).copy(citizenDid = None)

      val event = CitizenBiosampleEvent.forCreate(request)

      when(biosampleRepo.findByAccession(anyString()))
        .thenReturn(Future.successful(None))

      // Verify the DID is extracted correctly by checking the donor lookup
      when(donorRepo.findByDidAndIdentifier("did:plc:abc123xyz", "Subject-001"))
        .thenReturn(Future.successful(None))

      when(donorRepo.create(any[SpecimenDonor]))
        .thenAnswer(new Answer[Future[SpecimenDonor]] {
          override def answer(invocation: InvocationOnMock): Future[SpecimenDonor] = {
            val donor = invocation.getArgument[SpecimenDonor](0)
            // Verify the atUri on the donor is the extracted DID
            donor.atUri mustBe Some("did:plc:abc123xyz")
            Future.successful(donor.copy(id = Some(1)))
          }
        })

      when(biosampleRepo.create(any[CitizenBiosample]))
        .thenAnswer(new Answer[Future[CitizenBiosample]] {
          override def answer(invocation: InvocationOnMock): Future[CitizenBiosample] = {
            Future.successful(invocation.getArgument[CitizenBiosample](0).copy(id = Some(1)))
          }
        })

      when(dataService.addSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(donorRepo).findByDidAndIdentifier("did:plc:abc123xyz", "Subject-001")
      }
    }

    "handle missing donorIdentifier gracefully (no donor created)" in {
      val (biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo) = createMocks()

      val request = createRequest(donorIdentifier = None)
      val event = CitizenBiosampleEvent.forCreate(request)

      when(biosampleRepo.findByAccession(anyString()))
        .thenReturn(Future.successful(None))

      when(biosampleRepo.create(any[CitizenBiosample]))
        .thenAnswer(new Answer[Future[CitizenBiosample]] {
          override def answer(invocation: InvocationOnMock): Future[CitizenBiosample] = {
            val bs = invocation.getArgument[CitizenBiosample](0)
            bs.specimenDonorId mustBe None
            Future.successful(bs.copy(id = Some(100)))
          }
        })

      when(dataService.addSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      val handler = new CitizenBiosampleEventHandler(
        biosampleRepo, dataService, pubRepo, pubBioRepo, haplogroupRepo, donorRepo
      )

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(donorRepo, never).findByDidAndIdentifier(anyString(), anyString())
        verify(donorRepo, never).create(any[SpecimenDonor])
      }
    }
  }
}
