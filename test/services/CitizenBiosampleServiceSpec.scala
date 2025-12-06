package services

import models.api.{ExternalBiosampleRequest, SequenceDataInfo}
import models.domain.genomics.{BiologicalSex, BiosampleType}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import services.firehose.{CitizenBiosampleEvent, CitizenBiosampleEventHandler, FirehoseResult}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class CitizenBiosampleServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  def createRequest(
    atUri: String = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1",
    accession: String = "TEST-001"
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
    donorIdentifier = Some("Subject-001"),
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

  "CitizenBiosampleService" should {

    "delegate create to event handler and return GUID on success" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]
      val expectedGuid = UUID.randomUUID()

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.Success(
          atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1",
          newAtCid = "new-cid-123",
          sampleGuid = Some(expectedGuid),
          message = "Created"
        )))

      val service = new CitizenBiosampleService(mockHandler)
      val request = createRequest()

      whenReady(service.createBiosample(request)) { guid =>
        guid mustBe expectedGuid
        verify(mockHandler).handle(any[CitizenBiosampleEvent])
      }
    }

    "translate Conflict result to exception on create" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.Conflict(
          atUri = "at://test",
          message = "Biosample already exists"
        )))

      val service = new CitizenBiosampleService(mockHandler)
      val request = createRequest()

      whenReady(service.createBiosample(request).failed) { e =>
        e mustBe a[IllegalArgumentException]
        e.getMessage must include("already exists")
      }
    }

    "translate ValidationError result to exception on create" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.ValidationError(
          atUri = "at://test",
          message = "Invalid coordinates"
        )))

      val service = new CitizenBiosampleService(mockHandler)
      val request = createRequest()

      whenReady(service.createBiosample(request).failed) { e =>
        e mustBe a[IllegalArgumentException]
        e.getMessage must include("Invalid coordinates")
      }
    }

    "delegate update to event handler and return GUID on success" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]
      val expectedGuid = UUID.randomUUID()
      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1"

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.Success(
          atUri = atUri,
          newAtCid = "updated-cid",
          sampleGuid = Some(expectedGuid),
          message = "Updated"
        )))

      val service = new CitizenBiosampleService(mockHandler)
      val request = createRequest(atUri = atUri)

      whenReady(service.updateBiosample(atUri, request)) { guid =>
        guid mustBe expectedGuid
        verify(mockHandler).handle(any[CitizenBiosampleEvent])
      }
    }

    "translate NotFound result to exception on update" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]
      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/nonexistent"

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.NotFound(atUri)))

      val service = new CitizenBiosampleService(mockHandler)
      val request = createRequest(atUri = atUri)

      whenReady(service.updateBiosample(atUri, request).failed) { e =>
        e mustBe a[NoSuchElementException]
        e.getMessage must include("not found")
      }
    }

    "translate Conflict result to IllegalStateException on update" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]
      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1"

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.Conflict(
          atUri = atUri,
          message = "Optimistic locking failure"
        )))

      val service = new CitizenBiosampleService(mockHandler)
      val request = createRequest(atUri = atUri)

      whenReady(service.updateBiosample(atUri, request).failed) { e =>
        e mustBe a[IllegalStateException]
        e.getMessage must include("Optimistic locking")
      }
    }

    "delegate delete to event handler and return true on success" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]
      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/rkey1"

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.Success(
          atUri = atUri,
          newAtCid = "",
          sampleGuid = None,
          message = "Deleted"
        )))

      val service = new CitizenBiosampleService(mockHandler)

      whenReady(service.deleteBiosample(atUri)) { result =>
        result mustBe true
        verify(mockHandler).handle(any[CitizenBiosampleEvent])
      }
    }

    "return false when delete finds no record" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]
      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/nonexistent"

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.NotFound(atUri)))

      val service = new CitizenBiosampleService(mockHandler)

      whenReady(service.deleteBiosample(atUri)) { result =>
        result mustBe false
      }
    }

    "translate Error result to exception" in {
      val mockHandler = mock[CitizenBiosampleEventHandler]
      val cause = new RuntimeException("Database connection failed")

      when(mockHandler.handle(any[CitizenBiosampleEvent]))
        .thenReturn(Future.successful(FirehoseResult.Error(
          atUri = "at://test",
          message = "Database connection failed",
          cause = Some(cause)
        )))

      val service = new CitizenBiosampleService(mockHandler)
      val request = createRequest()

      whenReady(service.createBiosample(request).failed) { e =>
        e mustBe cause
      }
    }
  }
}
