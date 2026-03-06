package services

import helpers.ServiceSpec
import models.api.BiosampleUpdate
import models.domain.genomics.*
import models.domain.publications.PublicationBiosample
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito.{never, verify, when}
import repositories.{BiosampleOriginalHaplogroupRepository, BiosampleRepository, PublicationBiosampleRepository, SpecimenDonorRepository}

import org.mockito.Mockito.reset

import java.util.UUID
import scala.concurrent.Future

class BiosampleUpdateServiceSpec extends ServiceSpec {

  val mockBiosampleRepo: BiosampleRepository = mock[BiosampleRepository]
  val mockPubBiosampleRepo: PublicationBiosampleRepository = mock[PublicationBiosampleRepository]
  val mockHaplogroupRepo: BiosampleOriginalHaplogroupRepository = mock[BiosampleOriginalHaplogroupRepository]
  val mockDonorRepo: SpecimenDonorRepository = mock[SpecimenDonorRepository]

  val service = new BiosampleUpdateService(
    mockBiosampleRepo, mockPubBiosampleRepo, mockHaplogroupRepo, mockDonorRepo
  )

  override def beforeEach(): Unit = {
    reset(mockBiosampleRepo, mockPubBiosampleRepo, mockHaplogroupRepo, mockDonorRepo)
  }

  val sampleGuid: UUID = UUID.randomUUID()

  val testBiosample: Biosample = Biosample(
    id = Some(1),
    sampleGuid = sampleGuid,
    sampleAccession = "SAMEA001",
    description = "Test sample",
    alias = Some("alias1"),
    centerName = "TestCenter",
    specimenDonorId = Some(10),
    locked = false,
    sourcePlatform = Some("test")
  )

  val testDonor: SpecimenDonor = SpecimenDonor(
    id = Some(10),
    donorIdentifier = "DONOR_001",
    originBiobank = "TestBank",
    donorType = BiosampleType.Standard,
    sex = Some(BiologicalSex.Male),
    geocoord = None,
    dateRangeStart = None,
    dateRangeEnd = None
  )

  "BiosampleUpdateService" should {

    "return Left when no updates provided" in {
      val update = BiosampleUpdate()

      whenReady(service.updateBiosample(1, update)) { result =>
        result mustBe Left("No valid fields to update")
      }
    }

    "return Left when biosample not found" in {
      val update = BiosampleUpdate(alias = Some("new-alias"))
      when(mockBiosampleRepo.findById(999)).thenReturn(Future.successful(None))

      whenReady(service.updateBiosample(999, update)) { result =>
        result mustBe Left("Biosample not found")
      }
    }

    "update alias successfully" in {
      val update = BiosampleUpdate(alias = Some("new-alias"))

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((testBiosample, Some(testDonor)))))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        result.toOption.get.alias mustBe Some("new-alias")
      }
    }

    "update locked field" in {
      val update = BiosampleUpdate(locked = Some(true))

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((testBiosample, Some(testDonor)))))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        result.toOption.get.locked mustBe true
      }
    }

    "return Left when repository update fails" in {
      val update = BiosampleUpdate(alias = Some("new-alias"))

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((testBiosample, Some(testDonor)))))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(false))

      whenReady(service.updateBiosample(1, update)) { result =>
        result mustBe Left("Failed to update biosample")
      }
    }

    "update existing specimen donor sex" in {
      val update = BiosampleUpdate(sex = Some(BiologicalSex.Female))

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((testBiosample, Some(testDonor)))))
      when(mockDonorRepo.update(any[SpecimenDonor])).thenReturn(Future.successful(true))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        verify(mockDonorRepo).update(any[SpecimenDonor])
      }
    }

    "set donor type to Ancient when date range provided" in {
      val update = BiosampleUpdate(dateRangeStart = Some(-3000))

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((testBiosample, Some(testDonor)))))
      when(mockDonorRepo.update(any[SpecimenDonor])).thenReturn(Future.successful(true))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        verify(mockDonorRepo).update(any[SpecimenDonor])
      }
    }

    "not create new donor with only one identifying field" in {
      val biosampleNoDonor = testBiosample.copy(specimenDonorId = None)
      val update = BiosampleUpdate(sex = Some(BiologicalSex.Male))

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((biosampleNoDonor, None))))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        verify(mockDonorRepo, never()).create(any[SpecimenDonor])
      }
    }

    "create new donor when two or more identifying fields provided" in {
      val biosampleNoDonor = testBiosample.copy(specimenDonorId = None)
      val update = BiosampleUpdate(
        sex = Some(BiologicalSex.Female),
        dateRangeStart = Some(-5000)
      )

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((biosampleNoDonor, None))))
      when(mockDonorRepo.create(any[SpecimenDonor])).thenReturn(Future.successful(testDonor))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        verify(mockDonorRepo).create(any[SpecimenDonor])
      }
    }

    "update existing haplogroup record via upsert" in {
      val hapResult = HaplogroupResult("R-M269", 0.99, 100, 0, 50, 5, Seq("R", "R-M269"))
      val update = BiosampleUpdate(yHaplogroup = Some(hapResult))

      val existingEntry = OriginalHaplogroupEntry(
        publicationId = 10,
        yHaplogroupResult = None, mtHaplogroupResult = None, notes = None
      )

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((testBiosample, Some(testDonor)))))
      when(mockPubBiosampleRepo.findByBiosampleId(1)).thenReturn(Future.successful(Seq(PublicationBiosample(10, 1))))
      when(mockHaplogroupRepo.findByBiosampleId(1)).thenReturn(Future.successful(Seq(existingEntry)))
      when(mockHaplogroupRepo.upsert(anyInt, any[OriginalHaplogroupEntry])).thenReturn(Future.successful(true))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        verify(mockHaplogroupRepo).upsert(anyInt, any[OriginalHaplogroupEntry])
      }
    }

    "create new haplogroup record when none exists for publication" in {
      val hapResult = HaplogroupResult("R-M269", 0.99, 100, 0, 50, 5, Seq("R", "R-M269"))
      val update = BiosampleUpdate(yHaplogroup = Some(hapResult))

      when(mockBiosampleRepo.findById(1)).thenReturn(Future.successful(Some((testBiosample, Some(testDonor)))))
      when(mockPubBiosampleRepo.findByBiosampleId(1)).thenReturn(Future.successful(Seq(PublicationBiosample(10, 1))))
      when(mockHaplogroupRepo.findByBiosampleId(1)).thenReturn(Future.successful(Seq.empty))
      when(mockHaplogroupRepo.upsert(anyInt, any[OriginalHaplogroupEntry])).thenReturn(Future.successful(true))
      when(mockBiosampleRepo.update(any[Biosample])).thenReturn(Future.successful(true))

      whenReady(service.updateBiosample(1, update)) { result =>
        result.isRight mustBe true
        verify(mockHaplogroupRepo).upsert(anyInt, any[OriginalHaplogroupEntry])
      }
    }
  }
}
