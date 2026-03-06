package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.haplogroups.{AgeEstimate, Haplogroup}
import play.api.Logging
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * SNP-based branch age estimation using a Poisson mutation model.
 *
 * Core formula: P(t|m) = Poisson(m, t*b*µ)
 * Where:
 *   t = time in years
 *   b = callable loci (base pairs of coverage)
 *   µ = SNP mutation rate per bp per year
 *   m = observed mutations (SNP count)
 *
 * The point estimate (median) comes from: t = m / (b * µ)
 * 95% confidence intervals use Poisson quantiles.
 */
class BranchAgeEstimationService @Inject()(
  haplogroupCoreRepo: HaplogroupCoreRepository,
  haplogroupVariantRepo: HaplogroupVariantRepository
)(implicit ec: ExecutionContext) extends Logging {

  // Default SNP mutation rate: ~8.33 × 10⁻¹⁰ SNPs/bp/year (Helgason 2015)
  val DefaultMutationRate: Double = 8.33e-10
  val DefaultMutationRateSigma: Double = 0.4e-10

  // Default callable loci for Y-DNA BigY-700 test (~15 Mbp)
  val DefaultCallableLoci: Long = 15_000_000L

  /**
   * Calculate age estimate for a single haplogroup based on its defining SNP count.
   *
   * @param haplogroupId The haplogroup to estimate age for
   * @param callableLoci Callable base pairs (defaults to BigY-700 coverage)
   * @param mutationRate SNP mutation rate per bp per year
   * @return Age estimate with 95% confidence interval
   */
  def calculateAge(
    haplogroupId: Int,
    callableLoci: Long = DefaultCallableLoci,
    mutationRate: Double = DefaultMutationRate
  ): Future[Option[AgeEstimateResult]] = {
    for {
      haplogroupOpt <- haplogroupCoreRepo.findById(haplogroupId)
      variants <- haplogroupVariantRepo.getHaplogroupVariants(haplogroupId)
    } yield {
      haplogroupOpt.map { haplogroup =>
        val snpCount = variants.size
        calculateFromSnpCount(snpCount, callableLoci, mutationRate, haplogroup.name)
      }
    }
  }

  /**
   * Calculate age from a raw SNP count.
   */
  private[services] def calculateFromSnpCount(
    snpCount: Int,
    callableLoci: Long,
    mutationRate: Double,
    haplogroupName: String = ""
  ): AgeEstimateResult = {
    if (snpCount == 0) {
      return AgeEstimateResult(
        estimate = AgeEstimate(0, Some(0), Some(0)),
        snpCount = 0,
        callableLoci = callableLoci,
        mutationRate = mutationRate,
        method = "SNP_POISSON"
      )
    }

    val lambda = callableLoci.toDouble * mutationRate
    // Point estimate: t = m / (b * µ)
    val pointEstimate = snpCount.toDouble / lambda
    val pointYbp = math.round(pointEstimate).toInt

    // 95% confidence interval using Poisson quantiles
    // Lower bound: use lower Poisson quantile for mutation count
    // Upper bound: use upper Poisson quantile for mutation count
    val (lowerSnps, upperSnps) = poissonConfidenceInterval(snpCount, 0.95)
    val lowerYbp = math.round(lowerSnps / lambda).toInt
    val upperYbp = math.round(upperSnps / lambda).toInt

    if (haplogroupName.nonEmpty) {
      logger.debug(s"Age estimate for $haplogroupName: $pointYbp YBP ($lowerYbp–$upperYbp) from $snpCount SNPs")
    }

    AgeEstimateResult(
      estimate = AgeEstimate(pointYbp, Some(lowerYbp), Some(upperYbp)),
      snpCount = snpCount,
      callableLoci = callableLoci,
      mutationRate = mutationRate,
      method = "SNP_POISSON"
    )
  }

  /**
   * Calculate TMRCA between two sibling haplogroups.
   * TMRCA = (SNPs_on_child1 + SNPs_on_child2) / (2 * b * µ)
   */
  def calculateTmrca(
    childId1: Int,
    childId2: Int,
    callableLoci: Long = DefaultCallableLoci,
    mutationRate: Double = DefaultMutationRate
  ): Future[Option[AgeEstimateResult]] = {
    for {
      variants1 <- haplogroupVariantRepo.getHaplogroupVariants(childId1)
      variants2 <- haplogroupVariantRepo.getHaplogroupVariants(childId2)
    } yield {
      val totalSnps = variants1.size + variants2.size
      if (totalSnps == 0) None
      else {
        val lambda = 2.0 * callableLoci.toDouble * mutationRate
        val pointEstimate = totalSnps.toDouble / lambda
        val pointYbp = math.round(pointEstimate).toInt

        val (lowerSnps, upperSnps) = poissonConfidenceInterval(totalSnps, 0.95)
        val lowerYbp = math.round(lowerSnps / lambda).toInt
        val upperYbp = math.round(upperSnps / lambda).toInt

        Some(AgeEstimateResult(
          estimate = AgeEstimate(pointYbp, Some(lowerYbp), Some(upperYbp)),
          snpCount = totalSnps,
          callableLoci = callableLoci,
          mutationRate = mutationRate,
          method = "SNP_POISSON_TMRCA"
        ))
      }
    }
  }

  /**
   * Recalculate ages for an entire subtree bottom-up.
   * Applies causality constraint: parent must be older than any child.
   */
  def recalculateSubtree(
    rootId: Int,
    callableLoci: Long = DefaultCallableLoci,
    mutationRate: Double = DefaultMutationRate
  ): Future[Seq[AgeUpdateResult]] = {
    recalculateNode(rootId, callableLoci, mutationRate)
  }

  /**
   * Recursively calculate ages bottom-up, applying causality constraint.
   */
  private def recalculateNode(
    haplogroupId: Int,
    callableLoci: Long,
    mutationRate: Double
  ): Future[Seq[AgeUpdateResult]] = {
    for {
      // First recurse into children
      children <- haplogroupCoreRepo.getDirectChildren(haplogroupId)
      childResults <- Future.sequence(children.flatMap(_.id).map { childId =>
        recalculateNode(childId, callableLoci, mutationRate)
      })

      // Calculate this node's age
      resultOpt <- calculateAge(haplogroupId, callableLoci, mutationRate)

      // Apply causality: if any child is older, adjust this node
      childAges = childResults.flatten.filter(_.haplogroupId == haplogroupId).flatMap(r => Some(r.newEstimate.ybp)) ++
        children.flatMap(_.id).flatMap { childId =>
          childResults.flatten.find(_.haplogroupId == childId).map(_.newEstimate.ybp)
        }
      maxChildAge = if (childAges.isEmpty) 0 else childAges.max

      adjustedResult = resultOpt.map { result =>
        if (result.estimate.ybp < maxChildAge) {
          // Parent must be older than oldest child — adjust upward
          val adjusted = result.copy(
            estimate = result.estimate.copy(
              ybp = maxChildAge + 1, // At least 1 year older
              ybpLower = result.estimate.ybpLower.map(l => math.max(l, maxChildAge + 1)),
              ybpUpper = result.estimate.ybpUpper
            ),
            method = "SNP_POISSON_CAUSALITY_ADJUSTED"
          )
          logger.debug(s"Causality adjustment for haplogroup $haplogroupId: " +
            s"${result.estimate.ybp} -> ${adjusted.estimate.ybp} YBP (child max: $maxChildAge)")
          adjusted
        } else result
      }

      // Save result
      thisUpdate = adjustedResult.map { result =>
        AgeUpdateResult(
          haplogroupId = haplogroupId,
          newEstimate = result.estimate,
          previousEstimate = None, // Could look up existing, omitted for simplicity
          method = result.method,
          snpCount = result.snpCount
        )
      }.toSeq
    } yield childResults.flatten ++ thisUpdate
  }

  /**
   * Compute Poisson confidence interval for observed count m.
   * Uses the chi-squared relationship: 2*sum(Poisson) ~ chi-squared(2m)
   *
   * For a 95% CI:
   * - Lower: chi2_inv(0.025, 2m) / 2
   * - Upper: chi2_inv(0.975, 2(m+1)) / 2
   *
   * Approximated using the Wilson-Hilferty transformation for chi-squared.
   */
  private[services] def poissonConfidenceInterval(m: Int, confidence: Double): (Double, Double) = {
    val alpha = 1.0 - confidence

    if (m == 0) {
      // Special case: 0 observed mutations
      // Lower bound is 0, upper bound from chi-squared
      val upper = -0.5 * math.log(alpha / 2)  // Simplified for m=0
      return (0.0, upper * 2) // Approximate
    }

    // Normal approximation for Poisson CI (good for m >= 5)
    // Exact would use chi-squared quantiles, but this is sufficient for Phase 1
    val z = 1.96 // z-score for 95% CI
    val sqrtM = math.sqrt(m.toDouble)
    val lower = math.max(0, m.toDouble - z * sqrtM)
    val upper = m.toDouble + z * sqrtM

    (lower, upper)
  }

  /**
   * Temporal resolution in years per SNP for given callable loci.
   */
  private[services] def temporalResolution(
    callableLoci: Long,
    mutationRate: Double = DefaultMutationRate
  ): Double = {
    1.0 / (callableLoci.toDouble * mutationRate)
  }
}

/**
 * Result of an age estimation calculation.
 */
case class AgeEstimateResult(
  estimate: AgeEstimate,
  snpCount: Int,
  callableLoci: Long,
  mutationRate: Double,
  method: String
)

/**
 * Result of updating a haplogroup's age estimate.
 */
case class AgeUpdateResult(
  haplogroupId: Int,
  newEstimate: AgeEstimate,
  previousEstimate: Option[AgeEstimate],
  method: String,
  snpCount: Int
)
