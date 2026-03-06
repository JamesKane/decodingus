package services

import helpers.ServiceSpec
import models.dal.domain.genomics.{BiosampleVariantCall, HaplogroupCharacterState, StrMutationRate}
import models.domain.haplogroups.AgeEstimate
import org.mockito.ArgumentMatchers.{any, anyInt}
import org.mockito.Mockito.{reset, when}
import repositories.{BiosampleVariantCallRepository, HaplogroupCharacterStateRepository, StrMutationRateRepository}

import java.time.Instant
import scala.concurrent.Future

class StrAgeServiceSpec extends ServiceSpec {

  val mockStrRateRepo: StrMutationRateRepository = mock[StrMutationRateRepository]
  val mockCharStateRepo: HaplogroupCharacterStateRepository = mock[HaplogroupCharacterStateRepository]
  val mockVariantCallRepo: BiosampleVariantCallRepository = mock[BiosampleVariantCallRepository]

  val service = new StrAgeService(mockStrRateRepo, mockCharStateRepo, mockVariantCallRepo)

  override def beforeEach(): Unit = {
    reset(mockStrRateRepo, mockCharStateRepo, mockVariantCallRepo)
  }

  def makeRate(id: Int, markerName: String, rate: Double): StrMutationRate =
    StrMutationRate(
      id = Some(id), markerName = markerName,
      mutationRate = BigDecimal(rate),
      mutationRateLower = Some(BigDecimal(rate * 0.8)),
      mutationRateUpper = Some(BigDecimal(rate * 1.2)),
      source = Some("Ballantyne 2010")
    )

  val rate1: StrMutationRate = makeRate(1, "DYS456", 0.0048)
  val rate2: StrMutationRate = makeRate(2, "DYS389I", 0.0022)
  val rate3: StrMutationRate = makeRate(3, "DYS19", 0.0028)
  val rate4: StrMutationRate = makeRate(4, "DYS391", 0.0058)
  val rate5: StrMutationRate = makeRate(5, "DYS390", 0.0031)

  val allRates: Map[Int, StrMutationRate] = Map(1 -> rate1, 2 -> rate2, 3 -> rate3, 4 -> rate4, 5 -> rate5)

  "StrAgeService" should {

    "calculateFromGeneticDistance" should {

      "calculate age from matching markers" in {
        val ancestral = Map(1 -> 15, 2 -> 13, 3 -> 14, 4 -> 10, 5 -> 24)
        val observed  = Map(1 -> 16, 2 -> 13, 3 -> 15, 4 -> 11, 5 -> 23)
        // Distances: 1, 0, 1, 1, 1 = total 4

        val result = service.calculateFromGeneticDistance(ancestral, observed, allRates)

        result.markerCount mustBe 5
        result.totalGeneticDistance mustBe 4
        result.estimate.ybp must be > 0
        result.estimate.ybpLower mustBe defined
        result.estimate.ybpUpper mustBe defined
        result.method mustBe "STR_GENETIC_DISTANCE"
      }

      "return zero for identical haplotypes" in {
        val ancestral = Map(1 -> 15, 2 -> 13, 3 -> 14)
        val observed  = Map(1 -> 15, 2 -> 13, 3 -> 14)

        val result = service.calculateFromGeneticDistance(ancestral, observed, allRates)

        result.estimate.ybp mustBe 0
        result.totalGeneticDistance mustBe 0
        result.markerCount mustBe 3
      }

      "return zero when no common markers" in {
        val ancestral = Map(1 -> 15, 2 -> 13)
        val observed  = Map(3 -> 14, 4 -> 10)

        val result = service.calculateFromGeneticDistance(ancestral, observed, allRates)

        result.estimate.ybp mustBe 0
        result.markerCount mustBe 0
      }

      "handle partial marker overlap" in {
        val ancestral = Map(1 -> 15, 2 -> 13, 3 -> 14)
        val observed  = Map(1 -> 16, 3 -> 15, 4 -> 10) // only 1 and 3 overlap

        val result = service.calculateFromGeneticDistance(ancestral, observed, allRates)

        result.markerCount mustBe 2 // only variantIds 1 and 3 are common
        result.totalGeneticDistance mustBe 2 // |16-15| + |15-14|
      }

      "produce older estimate with larger genetic distance" in {
        val ancestral = Map(1 -> 15, 2 -> 13, 3 -> 14, 4 -> 10, 5 -> 24)
        val closeObserved = Map(1 -> 16, 2 -> 13, 3 -> 14, 4 -> 10, 5 -> 24) // distance 1
        val farObserved   = Map(1 -> 18, 2 -> 15, 3 -> 17, 4 -> 13, 5 -> 20) // distance 13

        val closeResult = service.calculateFromGeneticDistance(ancestral, closeObserved, allRates)
        val farResult   = service.calculateFromGeneticDistance(ancestral, farObserved, allRates)

        farResult.estimate.ybp must be > closeResult.estimate.ybp
      }

      "use generation length parameter" in {
        val ancestral = Map(1 -> 15, 2 -> 13)
        val observed  = Map(1 -> 18, 2 -> 15) // distance 5

        val result33 = service.calculateFromGeneticDistance(ancestral, observed, allRates, 33.0)
        val result25 = service.calculateFromGeneticDistance(ancestral, observed, allRates, 25.0)

        // Shorter generation = younger estimate
        result25.estimate.ybp must be < result33.estimate.ybp
      }
    }

    "calculateTmrcaFromStrs" should {

      "calculate TMRCA between two samples" in {
        val sample1 = Map(1 -> 15, 2 -> 13, 3 -> 14)
        val sample2 = Map(1 -> 17, 2 -> 12, 3 -> 16)
        // Distances: 2, 1, 2 = total 5

        val result = service.calculateTmrcaFromStrs(sample1, sample2, allRates)

        result.markerCount mustBe 3
        result.totalGeneticDistance mustBe 5
        result.estimate.ybp must be > 0
        result.method mustBe "STR_TMRCA"
      }

      "return zero for identical samples" in {
        val sample = Map(1 -> 15, 2 -> 13)
        val result = service.calculateTmrcaFromStrs(sample, sample, allRates)
        result.estimate.ybp mustBe 0
      }

      "produce younger TMRCA than single-lineage distance" in {
        // TMRCA divides by 2*rate (both lineages), so should be younger
        val ancestral = Map(1 -> 15, 2 -> 13, 3 -> 14)
        val observed  = Map(1 -> 18, 2 -> 15, 3 -> 16) // distance 6

        val singleLineage = service.calculateFromGeneticDistance(ancestral, observed, allRates)
        // For TMRCA, same total distance but divided by 2x rate
        val tmrca = service.calculateTmrcaFromStrs(ancestral, observed, allRates)

        tmrca.estimate.ybp must be < singleLineage.estimate.ybp
      }
    }

    "geneticDistanceConfidenceInterval" should {

      "return wider interval for zero distance" in {
        val (lower, upper) = service.geneticDistanceConfidenceInterval(0, 0.01, 5)
        lower mustBe 0.0
        upper must be > 0.0
      }

      "produce narrower relative CI with more distance" in {
        val (l5, u5) = service.geneticDistanceConfidenceInterval(5, 0.01, 10)
        val (l20, u20) = service.geneticDistanceConfidenceInterval(20, 0.01, 10)

        val relWidth5  = (u5 - l5) / 5.0
        val relWidth20 = (u20 - l20) / 20.0
        relWidth20 must be < relWidth5
      }
    }

    "multiStepProbability" should {

      "return correct probabilities" in {
        service.multiStepProbability(0) mustBe 1.0
        service.multiStepProbability(1) mustBe 0.962
        service.multiStepProbability(-1) mustBe 0.962
        service.multiStepProbability(2) mustBe 0.032
        service.multiStepProbability(-2) mustBe 0.032
        service.multiStepProbability(3) mustBe 0.004
        service.multiStepProbability(5) mustBe 0.001
      }
    }

    "calculateForBiosample" should {

      "return None when no ancestral states exist" in {
        when(mockCharStateRepo.findStrStatesForHaplogroup(anyInt, any[Seq[Int]]))
          .thenReturn(Future.successful(Seq.empty))
        when(mockVariantCallRepo.findByBiosampleAndVariants(anyInt, any[Seq[Int]]))
          .thenReturn(Future.successful(Seq.empty))
        when(mockStrRateRepo.findAll()).thenReturn(Future.successful(Seq.empty))

        whenReady(service.calculateForBiosample(1, 100, Seq(1, 2, 3))) { result =>
          result mustBe empty
        }
      }

      "return None when no observed values exist" in {
        val states = Seq(
          HaplogroupCharacterState(Some(1), 100, 1, "15"),
          HaplogroupCharacterState(Some(2), 100, 2, "13")
        )
        when(mockCharStateRepo.findStrStatesForHaplogroup(100, Seq(1, 2)))
          .thenReturn(Future.successful(states))
        when(mockVariantCallRepo.findByBiosampleAndVariants(1, Seq(1, 2)))
          .thenReturn(Future.successful(Seq.empty))
        when(mockStrRateRepo.findAll())
          .thenReturn(Future.successful(Seq(rate1, rate2)))

        whenReady(service.calculateForBiosample(1, 100, Seq(1, 2))) { result =>
          result mustBe empty
        }
      }
    }
  }

  "combineSnpAndStrEstimates" should {

    val snpService = new BranchAgeEstimationService(
      mock[repositories.HaplogroupCoreRepository],
      mock[repositories.HaplogroupVariantRepository],
      mock[repositories.BiosampleCallableLociRepository]
    )

    "produce narrower CI than either estimate alone" in {
      val snpResult = AgeEstimateResult(
        estimate = AgeEstimate(1000, Some(700), Some(1300)),
        snpCount = 12, callableLoci = 15_000_000L,
        mutationRate = 8.33e-10, method = "SNP_POISSON"
      )
      val strResult = StrAgeEstimateResult(
        estimate = AgeEstimate(900, Some(600), Some(1200)),
        markerCount = 20, totalGeneticDistance = 8,
        method = "STR_GENETIC_DISTANCE"
      )

      val combined = snpService.combineSnpAndStrEstimates(snpResult, strResult)

      combined.method mustBe "COMBINED_SNP_STR"
      val combinedWidth = combined.estimate.ybpUpper.get - combined.estimate.ybpLower.get
      val snpWidth = 1300 - 700
      val strWidth = 1200 - 600
      combinedWidth must be < snpWidth
      combinedWidth must be < strWidth
    }

    "weight toward more precise estimate" in {
      val preciseSnp = AgeEstimateResult(
        estimate = AgeEstimate(1000, Some(900), Some(1100)), // narrow CI
        snpCount = 50, callableLoci = 15_000_000L,
        mutationRate = 8.33e-10, method = "SNP_POISSON"
      )
      val impreciseStr = StrAgeEstimateResult(
        estimate = AgeEstimate(800, Some(400), Some(1200)), // wide CI
        markerCount = 5, totalGeneticDistance = 3,
        method = "STR_GENETIC_DISTANCE"
      )

      val combined = snpService.combineSnpAndStrEstimates(preciseSnp, impreciseStr)

      // Combined should be closer to the precise SNP estimate
      val distToSnp = math.abs(combined.estimate.ybp - 1000)
      val distToStr = math.abs(combined.estimate.ybp - 800)
      distToSnp must be < distToStr
    }

    "fall back to SNP-only when STR has zero age" in {
      val snpResult = AgeEstimateResult(
        estimate = AgeEstimate(1000, Some(700), Some(1300)),
        snpCount = 12, callableLoci = 15_000_000L,
        mutationRate = 8.33e-10, method = "SNP_POISSON"
      )
      val zeroStr = StrAgeEstimateResult(
        estimate = AgeEstimate(0, Some(0), Some(0)),
        markerCount = 0, totalGeneticDistance = 0,
        method = "STR_GENETIC_DISTANCE"
      )

      val combined = snpService.combineSnpAndStrEstimates(snpResult, zeroStr)

      combined.method mustBe "SNP_ONLY"
      combined.estimate.ybp mustBe 1000
    }

    "fall back to STR-only when SNP has zero age" in {
      val zeroSnp = AgeEstimateResult(
        estimate = AgeEstimate(0, Some(0), Some(0)),
        snpCount = 0, callableLoci = 15_000_000L,
        mutationRate = 8.33e-10, method = "SNP_POISSON"
      )
      val strResult = StrAgeEstimateResult(
        estimate = AgeEstimate(900, Some(600), Some(1200)),
        markerCount = 20, totalGeneticDistance = 8,
        method = "STR_GENETIC_DISTANCE"
      )

      val combined = snpService.combineSnpAndStrEstimates(zeroSnp, strResult)

      combined.method mustBe "STR_ONLY"
      combined.estimate.ybp mustBe 900
    }
  }
}
