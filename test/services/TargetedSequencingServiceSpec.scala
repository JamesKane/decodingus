package services

import helpers.ServiceSpec
import models.domain.genomics.*
import org.mockito.Mockito.{reset, when}
import repositories.{TestTypeRepository, TestTypeTargetRegionRepository}

import scala.concurrent.Future

class TargetedSequencingServiceSpec extends ServiceSpec {

  val mockTestTypeRepo: TestTypeRepository = mock[TestTypeRepository]
  val mockTargetRegionRepo: TestTypeTargetRegionRepository = mock[TestTypeTargetRegionRepository]

  val service = new TargetedSequencingService(mockTestTypeRepo, mockTargetRegionRepo)

  override def beforeEach(): Unit = {
    reset(mockTestTypeRepo, mockTargetRegionRepo)
  }

  val bigY700: TestTypeRow = TestTypeRow(
    id = Some(10), code = "BIG_Y_700", displayName = "FTDNA Big Y-700",
    category = DataGenerationMethod.Sequencing, vendor = Some("FamilyTreeDNA"),
    targetType = TargetType.YChromosome,
    expectedMinDepth = Some(30.0), expectedTargetDepth = Some(50.0),
    supportsHaplogroupY = true, supportsHaplogroupMt = false,
    supportsAutosomalIbd = false, supportsAncestry = false,
    typicalFileFormats = List("BAM", "VCF", "BED")
  )

  val mtFullSeq: TestTypeRow = TestTypeRow(
    id = Some(11), code = "MT_FULL_SEQUENCE", displayName = "mtDNA Full Sequence",
    category = DataGenerationMethod.Sequencing, vendor = Some("FamilyTreeDNA"),
    targetType = TargetType.MtDna,
    expectedMinDepth = Some(500.0), expectedTargetDepth = Some(1000.0),
    supportsHaplogroupY = false, supportsHaplogroupMt = true,
    supportsAutosomalIbd = false, supportsAncestry = false,
    typicalFileFormats = List("BAM", "FASTA", "VCF")
  )

  val bigY500: TestTypeRow = TestTypeRow(
    id = Some(12), code = "BIG_Y_500", displayName = "FTDNA Big Y-500 (Legacy)",
    category = DataGenerationMethod.Sequencing, vendor = Some("FamilyTreeDNA"),
    targetType = TargetType.YChromosome,
    successorTestTypeId = Some(10),
    supportsHaplogroupY = true, supportsHaplogroupMt = false,
    supportsAutosomalIbd = false, supportsAncestry = false,
    typicalFileFormats = List("BAM", "VCF", "BED")
  )

  val wgs: TestTypeRow = TestTypeRow(
    id = Some(1), code = "WGS", displayName = "Whole Genome Sequencing",
    category = DataGenerationMethod.Sequencing,
    targetType = TargetType.WholeGenome,
    supportsHaplogroupY = true, supportsHaplogroupMt = true,
    supportsAutosomalIbd = true, supportsAncestry = true,
    typicalFileFormats = List("BAM", "CRAM", "VCF")
  )

  val bigYRegion: TestTypeTargetRegion = TestTypeTargetRegion(
    id = Some(1), testTypeId = 10, contigName = "chrY",
    startPosition = Some(2781480), endPosition = Some(56887903),
    regionName = "Y Combbed Region", regionType = "TARGETED_SNPS",
    expectedCoveragePct = Some(0.95), expectedMinDepth = Some(30.0)
  )

  val mtRegion: TestTypeTargetRegion = TestTypeTargetRegion(
    id = Some(2), testTypeId = 11, contigName = "chrM",
    startPosition = Some(1), endPosition = Some(16569),
    regionName = "Full Mitochondrial Genome", regionType = "FULL",
    expectedCoveragePct = Some(0.999), expectedMinDepth = Some(500.0)
  )

  "TargetedSequencingService" should {

    "return capabilities for Big Y-700" in {
      when(mockTestTypeRepo.findByCode("BIG_Y_700")).thenReturn(Future.successful(Some(bigY700)))
      when(mockTargetRegionRepo.findByTestTypeId(10)).thenReturn(Future.successful(Seq(bigYRegion)))

      whenReady(service.getTargetedTestCapabilities("BIG_Y_700")) { result =>
        result mustBe defined
        val caps = result.get
        caps.supportsYDna mustBe true
        caps.supportsMtDna mustBe false
        caps.primaryContig mustBe Some("chrY")
        caps.totalTargetedBases mustBe Some(54106424L)
        caps.targetRegions must have size 1
      }
    }

    "return capabilities for mtDNA Full Sequence" in {
      when(mockTestTypeRepo.findByCode("MT_FULL_SEQUENCE")).thenReturn(Future.successful(Some(mtFullSeq)))
      when(mockTargetRegionRepo.findByTestTypeId(11)).thenReturn(Future.successful(Seq(mtRegion)))

      whenReady(service.getTargetedTestCapabilities("MT_FULL_SEQUENCE")) { result =>
        result mustBe defined
        val caps = result.get
        caps.supportsYDna mustBe false
        caps.supportsMtDna mustBe true
        caps.primaryContig mustBe Some("chrM")
        caps.totalTargetedBases mustBe Some(16569L)
      }
    }

    "return None for unknown test type" in {
      when(mockTestTypeRepo.findByCode("UNKNOWN")).thenReturn(Future.successful(None))

      whenReady(service.getTargetedTestCapabilities("UNKNOWN")) { result =>
        result mustBe None
      }
    }

    "assess coverage as HIGH when meeting expectations" in {
      when(mockTestTypeRepo.findByCode("BIG_Y_700")).thenReturn(Future.successful(Some(bigY700)))
      when(mockTargetRegionRepo.findByTestTypeId(10)).thenReturn(Future.successful(Seq(bigYRegion)))

      whenReady(service.assessCoverage("BIG_Y_700", Some(55.0), Some(0.96))) { result =>
        result mustBe defined
        val assessment = result.get
        assessment.qualityTier mustBe "HIGH"
        assessment.overallMeetsExpectation mustBe true
        assessment.targetRegions.head.meetsExpectation mustBe true
      }
    }

    "assess coverage as LOW when below expectations" in {
      when(mockTestTypeRepo.findByCode("BIG_Y_700")).thenReturn(Future.successful(Some(bigY700)))
      when(mockTargetRegionRepo.findByTestTypeId(10)).thenReturn(Future.successful(Seq(bigYRegion)))

      whenReady(service.assessCoverage("BIG_Y_700", Some(15.0), Some(0.60))) { result =>
        result mustBe defined
        val assessment = result.get
        assessment.qualityTier mustBe "LOW"
        assessment.overallMeetsExpectation mustBe false
        assessment.targetRegions.head.meetsExpectation mustBe false
      }
    }

    "assess mtDNA coverage correctly" in {
      when(mockTestTypeRepo.findByCode("MT_FULL_SEQUENCE")).thenReturn(Future.successful(Some(mtFullSeq)))
      when(mockTargetRegionRepo.findByTestTypeId(11)).thenReturn(Future.successful(Seq(mtRegion)))

      whenReady(service.assessCoverage("MT_FULL_SEQUENCE", Some(1200.0), Some(0.999))) { result =>
        result mustBe defined
        val assessment = result.get
        assessment.qualityTier mustBe "HIGH"
        assessment.overallMeetsExpectation mustBe true
      }
    }

    "return INSUFFICIENT for very low coverage" in {
      when(mockTestTypeRepo.findByCode("BIG_Y_700")).thenReturn(Future.successful(Some(bigY700)))
      when(mockTargetRegionRepo.findByTestTypeId(10)).thenReturn(Future.successful(Seq(bigYRegion)))

      whenReady(service.assessCoverage("BIG_Y_700", Some(5.0), Some(0.30))) { result =>
        result mustBe defined
        result.get.qualityTier mustBe "INSUFFICIENT"
      }
    }

    "return None when assessing coverage for type with no regions" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgs)))
      when(mockTargetRegionRepo.findByTestTypeId(1)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.assessCoverage("WGS", Some(30.0), Some(0.95))) { result =>
        result mustBe None
      }
    }

    "list targeted Y-DNA tests" in {
      when(mockTestTypeRepo.findByCapability(
        org.mockito.ArgumentMatchers.eq(Some(true)),
        org.mockito.ArgumentMatchers.any[Option[Boolean]],
        org.mockito.ArgumentMatchers.any[Option[Boolean]],
        org.mockito.ArgumentMatchers.any[Option[Boolean]]
      )).thenReturn(Future.successful(Seq(bigY700, wgs)))

      whenReady(service.getTargetedYTests) { tests =>
        tests must have size 1
        tests.head.code mustBe "BIG_Y_700"
      }
    }

    "list targeted mtDNA tests" in {
      when(mockTestTypeRepo.findByCapability(
        org.mockito.ArgumentMatchers.any[Option[Boolean]],
        org.mockito.ArgumentMatchers.eq(Some(true)),
        org.mockito.ArgumentMatchers.any[Option[Boolean]],
        org.mockito.ArgumentMatchers.any[Option[Boolean]]
      )).thenReturn(Future.successful(Seq(mtFullSeq, wgs)))

      whenReady(service.getTargetedMtTests) { tests =>
        tests must have size 1
        tests.head.code mustBe "MT_FULL_SEQUENCE"
      }
    }

    "find upgrade path from Big Y-500 to Big Y-700" in {
      when(mockTestTypeRepo.findByCode("BIG_Y_500")).thenReturn(Future.successful(Some(bigY500)))
      when(mockTestTypeRepo.getTestTypeRowsByIds(Seq(10))).thenReturn(Future.successful(Seq(bigY700)))

      whenReady(service.findUpgradePath("BIG_Y_500")) { result =>
        result mustBe defined
        result.get.code mustBe "BIG_Y_700"
      }
    }

    "return None for upgrade path when no successor" in {
      when(mockTestTypeRepo.findByCode("BIG_Y_700")).thenReturn(Future.successful(Some(bigY700)))

      whenReady(service.findUpgradePath("BIG_Y_700")) { result =>
        result mustBe None
      }
    }

    "identify targeted test types" in {
      when(mockTestTypeRepo.findByCode("BIG_Y_700")).thenReturn(Future.successful(Some(bigY700)))

      whenReady(service.isTargetedTest("BIG_Y_700")) { result =>
        result mustBe true
      }
    }

    "identify non-targeted test types" in {
      when(mockTestTypeRepo.findByCode("WGS")).thenReturn(Future.successful(Some(wgs)))

      whenReady(service.isTargetedTest("WGS")) { result =>
        result mustBe false
      }
    }

    "calculate quality tier boundaries correctly" in {
      TargetedCoverageAssessment.qualityTierFromCoverage(0.99) mustBe "HIGH"
      TargetedCoverageAssessment.qualityTierFromCoverage(0.95) mustBe "HIGH"
      TargetedCoverageAssessment.qualityTierFromCoverage(0.90) mustBe "MEDIUM"
      TargetedCoverageAssessment.qualityTierFromCoverage(0.80) mustBe "MEDIUM"
      TargetedCoverageAssessment.qualityTierFromCoverage(0.60) mustBe "LOW"
      TargetedCoverageAssessment.qualityTierFromCoverage(0.30) mustBe "INSUFFICIENT"
    }
  }
}
