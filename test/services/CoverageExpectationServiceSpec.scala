package services

import helpers.ServiceSpec
import models.domain.genomics.*
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{reset, when}
import repositories.{CoverageExpectationProfileRepository, TestTypeRepository}

import scala.concurrent.Future

class CoverageExpectationServiceSpec extends ServiceSpec {

  val mockProfileRepo: CoverageExpectationProfileRepository = mock[CoverageExpectationProfileRepository]
  val mockTestTypeRepo: TestTypeRepository = mock[TestTypeRepository]

  val service = new CoverageExpectationService(mockProfileRepo, mockTestTypeRepo)

  override def beforeEach(): Unit = {
    reset(mockProfileRepo, mockTestTypeRepo)
  }

  val wgsTestType: TestTypeRow = TestTypeRow(
    id = Some(1), code = "WGS", displayName = "Whole Genome Sequencing",
    category = DataGenerationMethod.Sequencing, vendor = Some("Illumina"),
    targetType = TargetType.WholeGenome,
    supportsHaplogroupY = true, supportsHaplogroupMt = true,
    supportsAutosomalIbd = true, supportsAncestry = true,
    typicalFileFormats = List("BAM", "CRAM")
  )

  val chipTestType: TestTypeRow = TestTypeRow(
    id = Some(5), code = "SNP_ARRAY_23ANDME", displayName = "23andMe v5 Chip",
    category = DataGenerationMethod.Genotyping, vendor = Some("23andMe"),
    targetType = TargetType.Mixed,
    expectedMarkerCount = Some(640000),
    supportsHaplogroupY = true, supportsHaplogroupMt = true,
    supportsAutosomalIbd = true, supportsAncestry = true,
    typicalFileFormats = List("TXT", "CSV")
  )

  val wgsYSnpProfile: CoverageExpectationProfile = CoverageExpectationProfile(
    id = Some(1), testTypeId = 1, contigName = "Y", variantClass = "SNP",
    minDepthHigh = 20.0, minDepthMedium = 10.0, minDepthLow = 5.0,
    minCoveragePct = Some(0.95), minMappingQuality = Some(30.0), minCallablePct = Some(0.90)
  )

  val wgsMtSnpProfile: CoverageExpectationProfile = CoverageExpectationProfile(
    id = Some(2), testTypeId = 1, contigName = "MT", variantClass = "SNP",
    minDepthHigh = 100.0, minDepthMedium = 50.0, minDepthLow = 20.0,
    minCoveragePct = Some(0.99), minMappingQuality = Some(30.0), minCallablePct = Some(0.95)
  )

  val wgsYStrProfile: CoverageExpectationProfile = CoverageExpectationProfile(
    id = Some(3), testTypeId = 1, contigName = "Y", variantClass = "STR",
    minDepthHigh = 30.0, minDepthMedium = 15.0, minDepthLow = 8.0,
    minCoveragePct = Some(0.90), minMappingQuality = Some(20.0)
  )

  val chipYSnpProfile: CoverageExpectationProfile = CoverageExpectationProfile(
    id = Some(10), testTypeId = 5, contigName = "Y", variantClass = "SNP",
    minDepthHigh = 0.0, minDepthMedium = 0.0, minDepthLow = 0.0,
    minCoveragePct = Some(0.0)
  )

  "CoverageExpectationService.assessVariantCallingConfidence" should {

    "return HIGH confidence for WGS with good depth" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq(wgsYSnpProfile)))

      val metrics = CoverageMetricsInput(
        meanDepth = Some(25.0), coveragePctAt1x = Some(0.98),
        meanMappingQuality = Some(40.0), callablePct = Some(0.95)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result mustBe defined
        val assessment = result.get
        assessment.testTypeCode mustBe "WGS"
        assessment.isChipBased mustBe false
        assessment.overallConfidence mustBe "high"
        assessment.confidences.head.depthConfidence mustBe "high"
        assessment.confidences.head.coverageAdequate mustBe true
        assessment.confidences.head.mappingQualityAdequate mustBe true
        assessment.confidences.head.callableBasesAdequate mustBe true
      }
    }

    "return MEDIUM confidence for WGS with moderate depth" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq(wgsYSnpProfile)))

      val metrics = CoverageMetricsInput(
        meanDepth = Some(12.0), coveragePctAt1x = Some(0.96),
        meanMappingQuality = Some(35.0), callablePct = Some(0.92)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result.get.overallConfidence mustBe "medium"
        result.get.confidences.head.depthConfidence mustBe "medium"
      }
    }

    "return LOW confidence for WGS with low depth" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq(wgsYSnpProfile)))

      val metrics = CoverageMetricsInput(
        meanDepth = Some(6.0), coveragePctAt1x = Some(0.96),
        meanMappingQuality = Some(35.0), callablePct = Some(0.92)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result.get.overallConfidence mustBe "low"
      }
    }

    "return INSUFFICIENT for WGS with very low depth" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq(wgsYSnpProfile)))

      val metrics = CoverageMetricsInput(
        meanDepth = Some(2.0), coveragePctAt1x = Some(0.96),
        meanMappingQuality = Some(35.0), callablePct = Some(0.92)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result.get.overallConfidence mustBe "insufficient"
      }
    }

    "downgrade confidence when coverage is below threshold" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq(wgsYSnpProfile)))

      val metrics = CoverageMetricsInput(
        meanDepth = Some(25.0), coveragePctAt1x = Some(0.80),
        meanMappingQuality = Some(40.0), callablePct = Some(0.95)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result.get.confidences.head.depthConfidence mustBe "high"
        result.get.confidences.head.coverageAdequate mustBe false
        result.get.overallConfidence mustBe "medium"
      }
    }

    "downgrade confidence when mapping quality is low" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq(wgsYSnpProfile)))

      val metrics = CoverageMetricsInput(
        meanDepth = Some(25.0), coveragePctAt1x = Some(0.98),
        meanMappingQuality = Some(15.0), callablePct = Some(0.95)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result.get.confidences.head.mappingQualityAdequate mustBe false
        result.get.overallConfidence mustBe "medium"
      }
    }

    "downgrade twice when multiple thresholds fail" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq(wgsYSnpProfile)))

      val metrics = CoverageMetricsInput(
        meanDepth = Some(25.0), coveragePctAt1x = Some(0.80),
        meanMappingQuality = Some(15.0), callablePct = Some(0.95)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result.get.overallConfidence mustBe "low"
      }
    }

    "use lowest confidence across multiple profiles" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(
        Future.successful(Seq(wgsYSnpProfile, wgsMtSnpProfile))
      )

      val metrics = CoverageMetricsInput(
        meanDepth = Some(25.0), coveragePctAt1x = Some(0.995),
        meanMappingQuality = Some(40.0), callablePct = Some(0.96)
      )

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        val assessment = result.get
        assessment.confidences.size mustBe 2
        val yConf = assessment.confidences.find(_.contigName == "Y").get
        val mtConf = assessment.confidences.find(_.contigName == "MT").get
        yConf.depthConfidence mustBe "high"
        mtConf.depthConfidence mustBe "low"
        assessment.overallConfidence mustBe "low"
      }
    }

    "return None for unknown test type" in {
      when(mockTestTypeRepo.findByCode("UNKNOWN")).thenReturn(Future.successful(None))

      val metrics = CoverageMetricsInput(meanDepth = Some(25.0))

      whenReady(service.assessVariantCallingConfidence("UNKNOWN", metrics)) { result =>
        result mustBe None
      }
    }

    "return None when no profiles exist" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq.empty))

      val metrics = CoverageMetricsInput(meanDepth = Some(25.0))

      whenReady(service.assessVariantCallingConfidence("WGS", metrics)) { result =>
        result mustBe None
      }
    }

    "handle chip-based test type with marker count" in {
      when(mockTestTypeRepo.findByCode("SNP_ARRAY_23ANDME")).thenReturn(Future.successful(Some(chipTestType)))
      when(mockProfileRepo.findByTestTypeId(5)).thenReturn(Future.successful(Seq(chipYSnpProfile)))

      val metrics = CoverageMetricsInput(markerCount = Some(2100), noCallRate = Some(0.02))

      whenReady(service.assessVariantCallingConfidence("SNP_ARRAY_23ANDME", metrics)) { result =>
        val assessment = result.get
        assessment.isChipBased mustBe true
        assessment.overallConfidence mustBe "high"
      }
    }

    "handle chip with low marker count" in {
      when(mockTestTypeRepo.findByCode("SNP_ARRAY_23ANDME")).thenReturn(Future.successful(Some(chipTestType)))
      when(mockProfileRepo.findByTestTypeId(5)).thenReturn(Future.successful(Seq(chipYSnpProfile)))

      val metrics = CoverageMetricsInput(markerCount = Some(50), noCallRate = Some(0.02))

      whenReady(service.assessVariantCallingConfidence("SNP_ARRAY_23ANDME", metrics)) { result =>
        result.get.overallConfidence mustBe "low"
      }
    }

    "downgrade chip confidence for high no-call rate" in {
      when(mockTestTypeRepo.findByCode("SNP_ARRAY_23ANDME")).thenReturn(Future.successful(Some(chipTestType)))
      when(mockProfileRepo.findByTestTypeId(5)).thenReturn(Future.successful(Seq(chipYSnpProfile)))

      val metrics = CoverageMetricsInput(markerCount = Some(2100), noCallRate = Some(0.10))

      whenReady(service.assessVariantCallingConfidence("SNP_ARRAY_23ANDME", metrics)) { result =>
        result.get.overallConfidence mustBe "medium"
      }
    }
  }

  "CoverageExpectationService.getConfidenceForVariant" should {

    "return confidence level for specific variant type" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeContigAndClass(1, "Y", "SNP"))
        .thenReturn(Future.successful(Some(wgsYSnpProfile)))

      whenReady(service.getConfidenceForVariant("WGS", "Y", "SNP", 25.0)) { result =>
        result mustBe Some("high")
      }
    }

    "return None when no profile matches" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeContigAndClass(1, "X", "SNP"))
        .thenReturn(Future.successful(None))

      whenReady(service.getConfidenceForVariant("WGS", "X", "SNP", 25.0)) { result =>
        result mustBe None
      }
    }
  }

  "CoverageExpectationService.getProfilesForTestType" should {

    "return profiles for known test type" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgsTestType)))
      when(mockProfileRepo.findByTestTypeId(1)).thenReturn(
        Future.successful(Seq(wgsYSnpProfile, wgsMtSnpProfile, wgsYStrProfile))
      )

      whenReady(service.getProfilesForTestType("WGS")) { result =>
        result.size mustBe 3
      }
    }

    "return empty for unknown test type" in {
      when(mockTestTypeRepo.findByCode("UNKNOWN")).thenReturn(Future.successful(None))

      whenReady(service.getProfilesForTestType("UNKNOWN")) { result =>
        result mustBe empty
      }
    }
  }

  "CoverageExpectationProfile.confidenceForDepth" should {

    "return high for depth above high threshold" in {
      wgsYSnpProfile.confidenceForDepth(25.0) mustBe "high"
    }

    "return high at exact high threshold" in {
      wgsYSnpProfile.confidenceForDepth(20.0) mustBe "high"
    }

    "return medium for depth between medium and high" in {
      wgsYSnpProfile.confidenceForDepth(15.0) mustBe "medium"
    }

    "return low for depth between low and medium" in {
      wgsYSnpProfile.confidenceForDepth(7.0) mustBe "low"
    }

    "return insufficient for depth below low threshold" in {
      wgsYSnpProfile.confidenceForDepth(3.0) mustBe "insufficient"
    }
  }

  "CoverageMetricsInput.fromEmbeddedCoverage" should {

    "create metrics from embedded coverage" in {
      val ec = EmbeddedCoverage(
        meanDepth = Some(25.0),
        percentCoverageAt1x = Some(0.98),
        meanMappingQuality = Some(40.0),
        basesCallable = Some(900000L),
        basesNoCoverage = Some(50000L),
        basesLowQualityMapping = Some(50000L)
      )

      val input = CoverageMetricsInput.fromEmbeddedCoverage(ec)
      input.meanDepth mustBe Some(25.0)
      input.coveragePctAt1x mustBe Some(0.98)
      input.meanMappingQuality mustBe Some(40.0)
      input.callablePct mustBe defined
      input.callablePct.get mustBe 0.9 +- 0.01
    }
  }

  "CoverageMetricsInput.fromGenotypeMetrics" should {

    "create metrics from genotype metrics" in {
      val gm = GenotypeMetrics(
        totalMarkersCalled = Some(625000),
        noCallRate = Some(0.023)
      )

      val input = CoverageMetricsInput.fromGenotypeMetrics(gm)
      input.markerCount mustBe Some(625000)
      input.noCallRate mustBe Some(0.023)
      input.meanDepth mustBe None
    }
  }

  "CoverageExpectationService.downgrade" should {

    "downgrade high to medium" in {
      service.downgrade("high") mustBe "medium"
    }

    "downgrade medium to low" in {
      service.downgrade("medium") mustBe "low"
    }

    "downgrade low to insufficient" in {
      service.downgrade("low") mustBe "insufficient"
    }

    "keep insufficient as insufficient" in {
      service.downgrade("insufficient") mustBe "insufficient"
    }
  }
}
