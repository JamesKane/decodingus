package services

import helpers.ServiceSpec
import models.api.*
import models.domain.genomics.*
import models.domain.publications.{Publication, PublicationBiosample}
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.*

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class BiosampleDataServiceSpec extends ServiceSpec {

  val mockBiosampleRepo: BiosampleRepository = mock[BiosampleRepository]
  val mockLibraryRepo: SequenceLibraryRepository = mock[SequenceLibraryRepository]
  val mockFileRepo: SequenceFileRepository = mock[SequenceFileRepository]
  val mockPubRepo: PublicationRepository = mock[PublicationRepository]
  val mockHaplogroupRepo: BiosampleOriginalHaplogroupRepository = mock[BiosampleOriginalHaplogroupRepository]
  val mockPubBiosampleRepo: PublicationBiosampleRepository = mock[PublicationBiosampleRepository]
  val mockTestTypeService: TestTypeService = mock[TestTypeService]

  val service = new BiosampleDataService(
    mockBiosampleRepo, mockLibraryRepo, mockFileRepo,
    mockPubRepo, mockHaplogroupRepo, mockPubBiosampleRepo, mockTestTypeService
  )

  override def beforeEach(): Unit = {
    reset(mockBiosampleRepo, mockLibraryRepo, mockFileRepo,
      mockPubRepo, mockHaplogroupRepo, mockPubBiosampleRepo, mockTestTypeService)
  }

  val sampleGuid: UUID = UUID.randomUUID()

  val testBiosample: Biosample = Biosample(
    id = Some(1), sampleGuid = sampleGuid, sampleAccession = "SAMEA001",
    description = "Test", alias = None, centerName = "Center",
    specimenDonorId = None, locked = false, sourcePlatform = None
  )

  val testTestType: TestTypeRow = TestTypeRow(
    id = Some(1), code = "WGS", displayName = "Whole Genome Sequencing",
    category = DataGenerationMethod.Sequencing, vendor = None,
    targetType = TargetType.WholeGenome,
    expectedMinDepth = None, expectedTargetDepth = None, expectedMarkerCount = None,
    supportsHaplogroupY = true, supportsHaplogroupMt = true,
    supportsAutosomalIbd = false, supportsAncestry = false,
    typicalFileFormats = List("CRAM", "BAM"), version = None,
    releaseDate = None, deprecatedAt = None, successorTestTypeId = None,
    description = None, documentationUrl = None
  )

  val testLibrary: SequenceLibrary = SequenceLibrary(
    id = Some(10), sampleGuid = sampleGuid, lab = "Illumina", testTypeId = 1,
    runDate = LocalDateTime.now(), instrument = "NovaSeq", reads = 1000,
    readLength = 150, pairedEnd = false, insertSize = None,
    atUri = None, atCid = None, created_at = LocalDateTime.now(), updated_at = None
  )

  val testSequenceData: SequenceDataInfo = SequenceDataInfo(
    reads = Some(1000), readLength = Some(150), coverage = Some(30.0),
    platformName = "Illumina", testType = "WGS", files = Seq.empty
  )

  val testPublication: Publication = Publication(
    id = Some(100), openAlexId = None, pubmedId = None,
    doi = Some("10.1234/test"), title = "Test Pub",
    authors = None, abstractSummary = None, journal = None,
    publicationDate = None, url = None, citationNormalizedPercentile = None,
    citedByCount = None, openAccessStatus = None, openAccessUrl = None,
    primaryTopic = None, publicationType = None, publisher = None
  )

  "BiosampleDataService" should {

    "addSequenceData with valid test type" in {
      when(mockTestTypeService.getByCode("WGS")).thenReturn(Future.successful(Some(testTestType)))
      when(mockLibraryRepo.create(any[SequenceLibrary])).thenReturn(Future.successful(testLibrary))

      whenReady(service.addSequenceData(sampleGuid, testSequenceData)) { _ =>
        verify(mockTestTypeService).getByCode("WGS")
        verify(mockLibraryRepo).create(any[SequenceLibrary])
      }
    }

    "addSequenceData fails with invalid test type" in {
      when(mockTestTypeService.getByCode("INVALID")).thenReturn(Future.successful(None))

      val data = testSequenceData.copy(testType = "INVALID")
      whenReady(service.addSequenceData(sampleGuid, data).failed) { ex =>
        ex mustBe a[IllegalArgumentException]
        ex.getMessage must include("Invalid test type code")
      }
    }

    "replaceSequenceData deletes existing then creates new" in {
      when(mockLibraryRepo.findBySampleGuid(sampleGuid)).thenReturn(Future.successful(Seq(testLibrary)))
      when(mockFileRepo.deleteByLibraryId(10)).thenReturn(Future.successful(1))
      when(mockLibraryRepo.delete(10)).thenReturn(Future.successful(true))
      when(mockTestTypeService.getByCode("WGS")).thenReturn(Future.successful(Some(testTestType)))
      when(mockLibraryRepo.create(any[SequenceLibrary])).thenReturn(Future.successful(testLibrary))

      whenReady(service.replaceSequenceData(sampleGuid, testSequenceData)) { _ =>
        verify(mockFileRepo).deleteByLibraryId(10)
        verify(mockLibraryRepo).delete(10)
        verify(mockLibraryRepo).create(any[SequenceLibrary])
      }
    }

    "replaceSequenceData with no existing libraries just creates new" in {
      when(mockLibraryRepo.findBySampleGuid(sampleGuid)).thenReturn(Future.successful(Seq.empty))
      when(mockTestTypeService.getByCode("WGS")).thenReturn(Future.successful(Some(testTestType)))
      when(mockLibraryRepo.create(any[SequenceLibrary])).thenReturn(Future.successful(testLibrary))

      whenReady(service.replaceSequenceData(sampleGuid, testSequenceData)) { _ =>
        verify(mockFileRepo, never()).deleteByLibraryId(anyInt)
        verify(mockLibraryRepo, never()).delete(anyInt)
        verify(mockLibraryRepo).create(any[SequenceLibrary])
      }
    }

    "linkPublication with existing publication by DOI" in {
      val pubInfo = PublicationInfo(doi = Some("10.1234/test"), pubmedId = None, originalHaplogroups = None)

      when(mockBiosampleRepo.findByGuid(sampleGuid)).thenReturn(Future.successful(Some((testBiosample, None))))
      when(mockPubRepo.findByDoi("10.1234/test")).thenReturn(Future.successful(Some(testPublication)))
      when(mockPubBiosampleRepo.create(any[PublicationBiosample])).thenReturn(Future.successful(PublicationBiosample(100, 1)))

      whenReady(service.linkPublication(sampleGuid, pubInfo)) { _ =>
        verify(mockPubRepo, never()).savePublication(any[Publication])
        verify(mockPubBiosampleRepo).create(any[PublicationBiosample])
      }
    }

    "linkPublication creates new publication when DOI not found" in {
      val pubInfo = PublicationInfo(doi = Some("10.9999/new"), pubmedId = None, originalHaplogroups = None)

      when(mockBiosampleRepo.findByGuid(sampleGuid)).thenReturn(Future.successful(Some((testBiosample, None))))
      when(mockPubRepo.findByDoi("10.9999/new")).thenReturn(Future.successful(None))
      when(mockPubRepo.savePublication(any[Publication])).thenReturn(Future.successful(testPublication.copy(doi = Some("10.9999/new"))))
      when(mockPubBiosampleRepo.create(any[PublicationBiosample])).thenReturn(Future.successful(PublicationBiosample(100, 1)))

      whenReady(service.linkPublication(sampleGuid, pubInfo)) { _ =>
        verify(mockPubRepo).savePublication(any[Publication])
      }
    }

    "linkPublication fails when biosample not found" in {
      val pubInfo = PublicationInfo(doi = Some("10.1234/test"), pubmedId = None, originalHaplogroups = None)

      when(mockBiosampleRepo.findByGuid(sampleGuid)).thenReturn(Future.successful(None))

      whenReady(service.linkPublication(sampleGuid, pubInfo).failed) { ex =>
        ex mustBe a[IllegalArgumentException]
        ex.getMessage must include("Biosample not found")
      }
    }

    "linkPublication creates haplogroup record when provided" in {
      val hapInfo = HaplogroupInfo(
        yHaplogroup = Some(HaplogroupResult("R-M269", 0.99, 100, 0, 50, 5, Seq("R"))),
        mtHaplogroup = None,
        notes = Some("test note")
      )
      val pubInfo = PublicationInfo(doi = Some("10.1234/test"), pubmedId = None, originalHaplogroups = Some(hapInfo))

      when(mockBiosampleRepo.findByGuid(sampleGuid)).thenReturn(Future.successful(Some((testBiosample, None))))
      when(mockPubRepo.findByDoi("10.1234/test")).thenReturn(Future.successful(Some(testPublication)))
      when(mockPubBiosampleRepo.create(any[PublicationBiosample])).thenReturn(Future.successful(PublicationBiosample(100, 1)))
      when(mockHaplogroupRepo.upsert(anyInt, any[OriginalHaplogroupEntry])).thenReturn(Future.successful(true))

      whenReady(service.linkPublication(sampleGuid, pubInfo)) { _ =>
        verify(mockHaplogroupRepo).upsert(anyInt, any[OriginalHaplogroupEntry])
      }
    }

    "fullyDeleteBiosampleAndDependencies deletes in correct order" in {
      when(mockPubBiosampleRepo.deleteByBiosampleId(1)).thenReturn(Future.successful(1))
      when(mockHaplogroupRepo.deleteAllByBiosampleId(1)).thenReturn(Future.successful(true))
      when(mockLibraryRepo.findBySampleGuid(sampleGuid)).thenReturn(Future.successful(Seq(testLibrary)))
      when(mockFileRepo.deleteByLibraryId(10)).thenReturn(Future.successful(1))
      when(mockLibraryRepo.delete(10)).thenReturn(Future.successful(true))
      when(mockBiosampleRepo.delete(1)).thenReturn(Future.successful(true))

      whenReady(service.fullyDeleteBiosampleAndDependencies(1, sampleGuid)) { _ =>
        verify(mockPubBiosampleRepo).deleteByBiosampleId(1)
        verify(mockHaplogroupRepo).deleteAllByBiosampleId(1)
        verify(mockFileRepo).deleteByLibraryId(10)
        verify(mockLibraryRepo).delete(10)
        verify(mockBiosampleRepo).delete(1)
      }
    }
  }
}
