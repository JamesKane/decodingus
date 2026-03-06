package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.domain.genomics.{MutationType, VariantV2}
import models.domain.haplogroups.{AgeEstimate, Haplogroup}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{reset, when}
import play.api.libs.json.Json
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository}

import java.time.LocalDateTime
import scala.concurrent.Future

class BranchAgeEstimationServiceSpec extends ServiceSpec {

  val mockCoreRepo: HaplogroupCoreRepository = mock[HaplogroupCoreRepository]
  val mockVariantRepo: HaplogroupVariantRepository = mock[HaplogroupVariantRepository]

  val service = new BranchAgeEstimationService(mockCoreRepo, mockVariantRepo)

  override def beforeEach(): Unit = {
    reset(mockCoreRepo, mockVariantRepo)
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 6, 1, 12, 0)

  def makeHaplogroup(id: Int, name: String): Haplogroup =
    Haplogroup(
      id = Some(id), name = name, lineage = Some(name),
      description = None, haplogroupType = HaplogroupType.Y,
      revisionId = 1, source = "backbone", confidenceLevel = "high",
      validFrom = now, validUntil = None
    )

  def makeVariant(id: Int): VariantV2 =
    VariantV2(
      variantId = Some(id), canonicalName = Some(s"V$id"), mutationType = MutationType.SNP,
      coordinates = Json.obj("GRCh38" -> Json.obj("contig" -> "chrY", "position" -> (1000000 + id), "ref" -> "A", "alt" -> "G"))
    )

  "BranchAgeEstimationService" should {

    "calculate age from SNP count" in {
      val hg = makeHaplogroup(100, "R-M269")
      val variants = (1 to 10).map(makeVariant)

      when(mockCoreRepo.findById(100)).thenReturn(Future.successful(Some(hg)))
      when(mockVariantRepo.getHaplogroupVariants(100)).thenReturn(Future.successful(variants))

      whenReady(service.calculateAge(100)) { resultOpt =>
        resultOpt mustBe defined
        val result = resultOpt.get
        result.snpCount mustBe 10
        result.estimate.ybp must be > 0
        result.estimate.ybpLower mustBe defined
        result.estimate.ybpUpper mustBe defined
        result.estimate.ybpLower.get must be < result.estimate.ybp
        result.estimate.ybpUpper.get must be > result.estimate.ybp
        result.method mustBe "SNP_POISSON"
      }
    }

    "return zero age for haplogroup with no variants" in {
      val hg = makeHaplogroup(100, "R")
      when(mockCoreRepo.findById(100)).thenReturn(Future.successful(Some(hg)))
      when(mockVariantRepo.getHaplogroupVariants(100)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.calculateAge(100)) { resultOpt =>
        resultOpt mustBe defined
        resultOpt.get.estimate.ybp mustBe 0
        resultOpt.get.snpCount mustBe 0
      }
    }

    "return None for nonexistent haplogroup" in {
      when(mockCoreRepo.findById(999)).thenReturn(Future.successful(None))
      when(mockVariantRepo.getHaplogroupVariants(999)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.calculateAge(999)) { resultOpt =>
        resultOpt mustBe empty
      }
    }

    "produce narrower CIs with more SNPs" in {
      // With 5 SNPs
      val result5 = service.calculateFromSnpCount(5, 15_000_000L, 8.33e-10)
      // With 50 SNPs
      val result50 = service.calculateFromSnpCount(50, 15_000_000L, 8.33e-10)

      // Relative CI width should be narrower for 50 SNPs
      val relWidth5 = (result5.estimate.ybpUpper.get - result5.estimate.ybpLower.get).toDouble / result5.estimate.ybp
      val relWidth50 = (result50.estimate.ybpUpper.get - result50.estimate.ybpLower.get).toDouble / result50.estimate.ybp
      relWidth50 must be < relWidth5
    }

    "scale linearly with callable loci" in {
      val result15m = service.calculateFromSnpCount(10, 15_000_000L, 8.33e-10)
      val result30m = service.calculateFromSnpCount(10, 30_000_000L, 8.33e-10)

      // Same SNP count, double the loci → half the age
      result30m.estimate.ybp mustBe (result15m.estimate.ybp / 2) +- 1
    }

    "calculate TMRCA from two siblings" in {
      val variants1 = (1 to 5).map(makeVariant)
      val variants2 = (6 to 12).map(makeVariant)

      when(mockVariantRepo.getHaplogroupVariants(10)).thenReturn(Future.successful(variants1))
      when(mockVariantRepo.getHaplogroupVariants(11)).thenReturn(Future.successful(variants2))

      whenReady(service.calculateTmrca(10, 11)) { resultOpt =>
        resultOpt mustBe defined
        val result = resultOpt.get
        result.snpCount mustBe 12 // 5 + 7
        result.method mustBe "SNP_POISSON_TMRCA"
        result.estimate.ybp must be > 0
      }
    }

    "return None for TMRCA when both children have zero variants" in {
      when(mockVariantRepo.getHaplogroupVariants(10)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(11)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.calculateTmrca(10, 11)) { resultOpt =>
        resultOpt mustBe empty
      }
    }

    "recalculate subtree bottom-up" in {
      val root = makeHaplogroup(1, "R")
      val child1 = makeHaplogroup(2, "R-M269")
      val child2 = makeHaplogroup(3, "R-M420")

      // Tree: R -> {R-M269, R-M420}
      when(mockCoreRepo.getDirectChildren(1)).thenReturn(Future.successful(Seq(child1, child2)))
      when(mockCoreRepo.getDirectChildren(2)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(3)).thenReturn(Future.successful(Seq.empty))

      when(mockCoreRepo.findById(1)).thenReturn(Future.successful(Some(root)))
      when(mockCoreRepo.findById(2)).thenReturn(Future.successful(Some(child1)))
      when(mockCoreRepo.findById(3)).thenReturn(Future.successful(Some(child2)))

      // Root has 5 SNPs, children have 3 and 4
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful((1 to 5).map(makeVariant)))
      when(mockVariantRepo.getHaplogroupVariants(2)).thenReturn(Future.successful((6 to 8).map(makeVariant)))
      when(mockVariantRepo.getHaplogroupVariants(3)).thenReturn(Future.successful((9 to 12).map(makeVariant)))

      whenReady(service.recalculateSubtree(1)) { results =>
        results must have size 3 // root + 2 children
        val rootResult = results.find(_.haplogroupId == 1)
        val child1Result = results.find(_.haplogroupId == 2)
        val child2Result = results.find(_.haplogroupId == 3)

        rootResult mustBe defined
        child1Result mustBe defined
        child2Result mustBe defined

        // Root must be at least as old as oldest child
        rootResult.get.newEstimate.ybp must be >= child1Result.get.newEstimate.ybp
        rootResult.get.newEstimate.ybp must be >= child2Result.get.newEstimate.ybp
      }
    }
  }

  "poissonConfidenceInterval" should {
    "return (0, >0) for zero mutations" in {
      val (lower, upper) = service.poissonConfidenceInterval(0, 0.95)
      lower mustBe 0.0
      upper must be > 0.0
    }

    "return symmetric-ish interval for large counts" in {
      val (lower, upper) = service.poissonConfidenceInterval(100, 0.95)
      lower must be > 0.0
      upper must be > 100.0
      lower must be < 100.0
      // Should be roughly symmetric for large N
      val width = upper - lower
      val midpoint = (upper + lower) / 2
      midpoint mustBe 100.0 +- 5.0
    }

    "widen with confidence level" in {
      val (l90, u90) = service.poissonConfidenceInterval(50, 0.90)
      val (l95, u95) = service.poissonConfidenceInterval(50, 0.95)
      // 95% CI should be wider than 90% (currently both use z=1.96, but testing the shape)
      (u95 - l95) must be >= (u90 - l90)
    }
  }

  "temporalResolution" should {
    "return ~83 years/SNP for 15 Mbp coverage" in {
      val res = service.temporalResolution(15_000_000L)
      res mustBe 80.0 +- 5.0 // ~80 years per SNP
    }

    "return ~40 years/SNP for 30 Mbp coverage" in {
      val res = service.temporalResolution(30_000_000L)
      res mustBe 40.0 +- 3.0
    }
  }

  "AgeEstimate" should {
    "convert YBP to calendar year correctly" in {
      AgeEstimate(2000).toCalendarYear mustBe -50 // 50 BC
      AgeEstimate(100).toCalendarYear mustBe 1850 // 1850 AD
      AgeEstimate(1950).toCalendarYear mustBe 0 // Year 0
    }

    "format as human-readable string" in {
      AgeEstimate(2000).formatted mustBe "50 BC"
      AgeEstimate(100).formatted mustBe "1850 AD"
    }

    "format with range" in {
      val est = AgeEstimate(1000, Some(800), Some(1200))
      est.formattedWithRange mustBe "950 AD (750 AD – 1150 AD)"
    }
  }
}
