package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.atmosphere.{HaplogroupAssignments, PrivateVariantData, VariantCall, HaplogroupResult as AtmoHaplogroupResult}
import models.domain.discovery.BiosamplePrivateVariant
import models.domain.genomics.*
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.GenotypeDataRepository

import java.util.UUID
import scala.concurrent.Future

class ChipDataRegistrationServiceSpec extends ServiceSpec {

  val mockTestTypeService: TestTypeService = mock[TestTypeService]
  val mockGenotypeRepo: GenotypeDataRepository = mock[GenotypeDataRepository]
  val mockPvService: PrivateVariantExtractionService = mock[PrivateVariantExtractionService]

  val service = new ChipDataRegistrationService(
    mockTestTypeService, mockGenotypeRepo, mockPvService
  )

  override def beforeEach(): Unit = {
    reset(mockTestTypeService, mockGenotypeRepo, mockPvService)
  }

  val chip23andMe: TestTypeRow = TestTypeRow(
    id = Some(5), code = "SNP_ARRAY_23ANDME", displayName = "23andMe v5 Chip",
    category = DataGenerationMethod.Genotyping, vendor = Some("23andMe"),
    targetType = TargetType.Mixed,
    expectedMarkerCount = Some(640000),
    supportsHaplogroupY = true, supportsHaplogroupMt = true,
    supportsAutosomalIbd = true, supportsAncestry = true,
    typicalFileFormats = List("TXT", "CSV")
  )

  val goodMetrics: GenotypeMetrics = GenotypeMetrics(
    totalMarkersCalled = Some(625000),
    totalMarkersPossible = Some(640000),
    callRate = Some(0.977),
    noCallRate = Some(0.023),
    yMarkersCalled = Some(2100),
    yMarkersTotal = Some(2200),
    mtMarkersCalled = Some(3100),
    mtMarkersTotal = Some(3200)
  )

  val sampleGuid: UUID = UUID.randomUUID()

  "ChipDataRegistrationService.assessQuality" should {

    "rate HIGH quality for good metrics" in {
      val result = service.assessQuality(goodMetrics, Some(chip23andMe))
      result.overallQuality mustBe "HIGH"
      result.noCallRateAcceptable mustBe true
      result.yDnaCoverage mustBe Some("SUFFICIENT")
      result.mtDnaCoverage mustBe Some("SUFFICIENT")
      result.warnings mustBe empty
    }

    "rate LOW quality for high no-call rate" in {
      val badMetrics = goodMetrics.copy(noCallRate = Some(0.10))
      val result = service.assessQuality(badMetrics, Some(chip23andMe))
      result.overallQuality mustBe "LOW"
      result.noCallRateAcceptable mustBe false
      result.warnings.exists(_.contains("No-call rate")) mustBe true
    }

    "rate LOW quality for insufficient markers" in {
      val fewMarkers = goodMetrics.copy(totalMarkersCalled = Some(50000))
      val result = service.assessQuality(fewMarkers, Some(chip23andMe))
      result.overallQuality mustBe "LOW"
      result.warnings.exists(_.contains("below minimum")) mustBe true
    }

    "warn when markers below expected for chip type" in {
      val lowMarkers = goodMetrics.copy(totalMarkersCalled = Some(500000))
      val result = service.assessQuality(lowMarkers, Some(chip23andMe))
      result.overallQuality mustBe "MEDIUM"
      result.warnings.exists(_.contains("% of expected")) mustBe true
    }

    "report LIMITED Y-DNA coverage for few markers" in {
      val fewY = goodMetrics.copy(yMarkersCalled = Some(20))
      val result = service.assessQuality(fewY, Some(chip23andMe))
      result.yDnaCoverage mustBe Some("LIMITED")
    }

    "report NONE Y-DNA coverage for zero markers" in {
      val noY = goodMetrics.copy(yMarkersCalled = Some(0))
      val result = service.assessQuality(noY, Some(chip23andMe))
      result.yDnaCoverage mustBe Some("NONE")
    }
  }

  "ChipDataRegistrationService.validateChipData" should {

    "accept valid chip data" in {
      when(mockTestTypeService.getByCode("SNP_ARRAY_23ANDME"))
        .thenReturn(Future.successful(Some(chip23andMe)))

      whenReady(service.validateChipData("SNP_ARRAY_23ANDME", "23andMe", goodMetrics)) { result =>
        result mustBe a[Right[?, ?]]
        result.toOption.get.code mustBe "SNP_ARRAY_23ANDME"
      }
    }

    "reject unknown test type" in {
      when(mockTestTypeService.getByCode("UNKNOWN"))
        .thenReturn(Future.successful(None))

      whenReady(service.validateChipData("UNKNOWN", "Foo", goodMetrics)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get.head must include("Unknown test type")
      }
    }

    "reject non-genotyping test type" in {
      val seqType = chip23andMe.copy(category = DataGenerationMethod.Sequencing)
      when(mockTestTypeService.getByCode("WGS"))
        .thenReturn(Future.successful(Some(seqType)))

      whenReady(service.validateChipData("WGS", "Illumina", goodMetrics)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get.exists(_.contains("not a genotyping test")) mustBe true
      }
    }

    "reject blank provider" in {
      when(mockTestTypeService.getByCode("SNP_ARRAY_23ANDME"))
        .thenReturn(Future.successful(Some(chip23andMe)))

      whenReady(service.validateChipData("SNP_ARRAY_23ANDME", "", goodMetrics)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get.exists(_.contains("Provider")) mustBe true
      }
    }

    "reject missing marker count" in {
      when(mockTestTypeService.getByCode("SNP_ARRAY_23ANDME"))
        .thenReturn(Future.successful(Some(chip23andMe)))
      val noMarkers = goodMetrics.copy(totalMarkersCalled = None)

      whenReady(service.validateChipData("SNP_ARRAY_23ANDME", "23andMe", noMarkers)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get.exists(_.contains("markers called is required")) mustBe true
      }
    }
  }

  "ChipDataRegistrationService.extractPrivateVariantsFromChip" should {

    "extract Y and mtDNA private variants" in {
      val yVariants = Seq(
        VariantCall("Y", 12345, "A", "G", Some("rs123"), Some("M269"), None, None, None),
        VariantCall("Y", 23456, "C", "T", None, None, None, None, None)
      )
      val mtVariants = Seq(
        VariantCall("MT", 1234, "A", "G", None, None, None, None, None)
      )

      val assignments = HaplogroupAssignments(
        yDna = Some(AtmoHaplogroupResult(
          haplogroupName = "R-M269",
          score = 0.95,
          matchingSnps = Some(50),
          mismatchingSnps = Some(2),
          ancestralMatches = None,
          treeDepth = Some(10),
          lineagePath = Some(Seq("R", "R1", "R1b", "R-M269")),
          privateVariants = Some(PrivateVariantData(Some(yVariants), None, None))
        )),
        mtDna = Some(AtmoHaplogroupResult(
          haplogroupName = "H2a",
          score = 0.99,
          matchingSnps = Some(30),
          mismatchingSnps = Some(0),
          ancestralMatches = None,
          treeDepth = Some(8),
          lineagePath = Some(Seq("H", "H2", "H2a")),
          privateVariants = Some(PrivateVariantData(Some(mtVariants), None, None))
        ))
      )

      when(mockPvService.extractFromCitizenBiosample(
        meq(1), meq(sampleGuid), meq("R-M269"), meq(HaplogroupType.Y), any[Seq[VariantCall]]
      )).thenReturn(Future.successful(Seq.fill(2)(mock[BiosamplePrivateVariant])))

      when(mockPvService.extractFromCitizenBiosample(
        meq(1), meq(sampleGuid), meq("H2a"), meq(HaplogroupType.MT), any[Seq[VariantCall]]
      )).thenReturn(Future.successful(Seq.fill(1)(mock[BiosamplePrivateVariant])))

      whenReady(service.extractPrivateVariantsFromChip(1, sampleGuid, Some(assignments))) { result =>
        result.yPrivateVariants mustBe 2
        result.mtPrivateVariants mustBe 1
        result.total mustBe 3
      }
    }

    "return zero when no assignments" in {
      whenReady(service.extractPrivateVariantsFromChip(1, sampleGuid, None)) { result =>
        result.yPrivateVariants mustBe 0
        result.mtPrivateVariants mustBe 0
        verify(mockPvService, never()).extractFromCitizenBiosample(
          any[Int], any[UUID], any[String], any[HaplogroupType], any[Seq[VariantCall]]
        )
      }
    }

    "return zero when no private variants in assignments" in {
      val assignments = HaplogroupAssignments(
        yDna = Some(AtmoHaplogroupResult(
          "R-M269", 0.95, Some(50), Some(2), None, Some(10),
          Some(Seq("R", "R-M269")), privateVariants = None
        )),
        mtDna = None
      )

      whenReady(service.extractPrivateVariantsFromChip(1, sampleGuid, Some(assignments))) { result =>
        result.yPrivateVariants mustBe 0
        result.mtPrivateVariants mustBe 0
      }
    }

    "handle extraction failure gracefully" in {
      val variants = Seq(VariantCall("Y", 12345, "A", "G", None, None, None, None, None))
      val assignments = HaplogroupAssignments(
        yDna = Some(AtmoHaplogroupResult(
          "R-M269", 0.95, Some(50), Some(2), None, Some(10),
          Some(Seq("R", "R-M269")),
          privateVariants = Some(PrivateVariantData(Some(variants), None, None))
        )),
        mtDna = None
      )

      when(mockPvService.extractFromCitizenBiosample(
        any[Int], any[UUID], any[String], any[HaplogroupType], any[Seq[VariantCall]]
      )).thenReturn(Future.failed(new RuntimeException("Tree lookup failed")))

      whenReady(service.extractPrivateVariantsFromChip(1, sampleGuid, Some(assignments))) { result =>
        result.yPrivateVariants mustBe 0
        result.mtPrivateVariants mustBe 0
      }
    }
  }

  "ChipDataRegistrationService.findExistingByHash" should {

    "detect duplicate by source file hash" in {
      val existing = GenotypeData(
        id = Some(1), sampleGuid = sampleGuid,
        sourceFileHash = Some("abc123hash")
      )
      when(mockGenotypeRepo.findBySourceFileHash("abc123hash"))
        .thenReturn(Future.successful(Some(existing)))

      whenReady(service.findExistingByHash("abc123hash")) { result =>
        result mustBe defined
        result.get.id mustBe Some(1)
      }
    }

    "return None when no duplicate" in {
      when(mockGenotypeRepo.findBySourceFileHash("newhash"))
        .thenReturn(Future.successful(None))

      whenReady(service.findExistingByHash("newhash")) { result =>
        result mustBe None
      }
    }
  }
}
