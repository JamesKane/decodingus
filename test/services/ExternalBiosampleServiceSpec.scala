package services

import helpers.ServiceSpec
import models.api.*
import models.domain.genomics.{Biosample, BiosampleType, SpecimenDonor}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.BiosampleRepository

import java.util.UUID
import scala.concurrent.Future

class ExternalBiosampleServiceSpec extends ServiceSpec {

  val mockBiosampleRepo: BiosampleRepository = mock[BiosampleRepository]
  val mockDataService: BiosampleDataService = mock[BiosampleDataService]
  val mockBiosampleService: BiosampleService = mock[BiosampleService]

  val service = new ExternalBiosampleService(mockBiosampleRepo, mockDataService, mockBiosampleService)

  override def beforeEach(): Unit = {
    reset(mockBiosampleRepo, mockDataService, mockBiosampleService)
  }

  val testSequenceData: SequenceDataInfo = SequenceDataInfo(
    reads = Some(1000),
    readLength = Some(150),
    coverage = Some(30.0),
    platformName = "Illumina",
    testType = "WGS",
    files = Seq.empty
  )

  def makeRequest(
    accession: String = "SAMEA001",
    publication: Option[PublicationInfo] = None,
    donorIdentifier: Option[String] = Some("DONOR_1"),
    citizenDid: Option[String] = None
  ): ExternalBiosampleRequest = ExternalBiosampleRequest(
    sampleAccession = accession,
    sourceSystem = "test",
    description = "Test sample",
    alias = Some("alias"),
    centerName = "TestCenter",
    sex = None,
    latitude = None,
    longitude = None,
    citizenDid = citizenDid,
    atUri = None,
    donorIdentifier = donorIdentifier,
    donorType = None,
    publication = publication,
    haplogroups = None,
    sequenceData = testSequenceData
  )

  val testGuid: UUID = UUID.randomUUID()

  val testBiosample: Biosample = Biosample(
    id = Some(1),
    sampleGuid = testGuid,
    sampleAccession = "SAMEA001",
    description = "Test",
    alias = Some("alias"),
    centerName = "TestCenter",
    specimenDonorId = None,
    locked = false,
    sourcePlatform = Some("test")
  )

  "ExternalBiosampleService" should {

    "create new biosample with data" in {
      val request = makeRequest()

      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.successful(Some(1)))
      when(mockBiosampleRepo.findByAccession("SAMEA001")).thenReturn(Future.successful(None))
      when(mockBiosampleService.createBiosample(any[UUID], anyString, anyString, any, anyString, any, any))
        .thenReturn(Future.successful(testBiosample))
      when(mockDataService.addSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      whenReady(service.createBiosampleWithData(request)) { guid =>
        guid mustBe a[UUID]
        verify(mockBiosampleService).createBiosample(any[UUID], anyString, anyString, any, anyString, any, any)
        verify(mockDataService).addSequenceData(any[UUID], any[SequenceDataInfo])
      }
    }

    "update existing biosample when accession found" in {
      val request = makeRequest()

      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.successful(Some(1)))
      when(mockBiosampleRepo.findByAccession("SAMEA001")).thenReturn(Future.successful(Some((testBiosample, None))))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))
      when(mockDataService.replaceSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      whenReady(service.createBiosampleWithData(request)) { guid =>
        guid mustBe testGuid
        verify(mockBiosampleRepo).update(any[Biosample])
        verify(mockDataService).replaceSequenceData(any[UUID], any[SequenceDataInfo])
      }
    }

    "skip publication linkage when none provided" in {
      val request = makeRequest(publication = None)

      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.successful(None))
      when(mockBiosampleRepo.findByAccession("SAMEA001")).thenReturn(Future.successful(None))
      when(mockBiosampleService.createBiosample(any[UUID], anyString, anyString, any, anyString, any, any))
        .thenReturn(Future.successful(testBiosample))
      when(mockDataService.addSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.successful(()))

      whenReady(service.createBiosampleWithData(request)) { guid =>
        guid mustBe a[UUID]
        verify(mockDataService, never()).linkPublication(any[UUID], any[PublicationInfo])
      }
    }

    "wrap publication linkage failure in PublicationLinkageException" in {
      val pubInfo = PublicationInfo(doi = Some("10.1234/test"), pubmedId = None, originalHaplogroups = None)
      val request = makeRequest(publication = Some(pubInfo))

      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.successful(None))
      when(mockBiosampleRepo.findByAccession("SAMEA001")).thenReturn(Future.successful(None))
      when(mockBiosampleService.createBiosample(any[UUID], anyString, anyString, any, anyString, any, any))
        .thenReturn(Future.successful(testBiosample))
      when(mockDataService.linkPublication(any[UUID], any[PublicationInfo]))
        .thenReturn(Future.failed(new RuntimeException("DOI not found")))

      whenReady(service.createBiosampleWithData(request).failed) { ex =>
        ex mustBe a[PublicationLinkageException]
      }
    }

    "wrap sequence data failure in SequenceDataValidationException" in {
      val request = makeRequest()

      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.successful(None))
      when(mockBiosampleRepo.findByAccession("SAMEA001")).thenReturn(Future.successful(None))
      when(mockBiosampleService.createBiosample(any[UUID], anyString, anyString, any, anyString, any, any))
        .thenReturn(Future.successful(testBiosample))
      when(mockDataService.addSequenceData(any[UUID], any[SequenceDataInfo]))
        .thenReturn(Future.failed(new RuntimeException("Invalid format")))

      whenReady(service.createBiosampleWithData(request).failed) { ex =>
        ex mustBe a[SequenceDataValidationException]
      }
    }

    "wrap unexpected exceptions in RuntimeException" in {
      val request = makeRequest()

      when(mockBiosampleService.createOrUpdateSpecimenDonor(anyString, anyString, any[BiosampleType], any, any, any, any, any))
        .thenReturn(Future.failed(new NullPointerException("unexpected")))

      whenReady(service.createBiosampleWithData(request).failed) { ex =>
        ex mustBe a[RuntimeException]
        ex.getMessage must include("Failed to process biosample")
      }
    }

    "delete biosample when found and owned by DID" in {
      val donor = SpecimenDonor(
        id = Some(1), donorIdentifier = "D1", originBiobank = "Bank",
        donorType = BiosampleType.Standard, sex = None, geocoord = None,
        atUri = Some("did:plc:abc123")
      )

      when(mockBiosampleRepo.findByAccession("SAMEA001")).thenReturn(Future.successful(Some((testBiosample, Some(donor)))))
      when(mockDataService.fullyDeleteBiosampleAndDependencies(any[Int], any[UUID]))
        .thenReturn(Future.successful(()))

      whenReady(service.deleteBiosample("SAMEA001", "did:plc:abc123")) { result =>
        result mustBe true
        verify(mockDataService).fullyDeleteBiosampleAndDependencies(any[Int], any[UUID])
      }
    }

    "return false when biosample not found" in {
      when(mockBiosampleRepo.findByAccession("SAMEA999")).thenReturn(Future.successful(None))

      whenReady(service.deleteBiosample("SAMEA999", "did:plc:abc123")) { result =>
        result mustBe false
      }
    }

    "return false when DID does not match" in {
      val donor = SpecimenDonor(
        id = Some(1), donorIdentifier = "D1", originBiobank = "Bank",
        donorType = BiosampleType.Standard, sex = None, geocoord = None,
        atUri = Some("did:plc:other")
      )

      when(mockBiosampleRepo.findByAccession("SAMEA001")).thenReturn(Future.successful(Some((testBiosample, Some(donor)))))

      whenReady(service.deleteBiosample("SAMEA001", "did:plc:abc123")) { result =>
        result mustBe false
        verify(mockDataService, never()).fullyDeleteBiosampleAndDependencies(any[Int], any[UUID])
      }
    }
  }
}
