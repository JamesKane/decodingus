package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.haplogroups.{AgeEstimate, Haplogroup}
import play.api.Logging
import repositories.{BiosampleCallableLociRepository, HaplogroupCoreRepository, HaplogroupVariantRepository}

import java.util.UUID
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
  haplogroupVariantRepo: HaplogroupVariantRepository,
  callableLociRepo: BiosampleCallableLociRepository
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
   * Look up per-sample callable loci, falling back to the default if not available.
   */
  def getCallableLociForSample(sampleGuid: UUID, chromosome: String = "chrY"): Future[Long] = {
    callableLociRepo.findBySampleGuid(sampleGuid, chromosome).map {
      case Some(loci) => loci.totalCallableBp
      case None => DefaultCallableLoci
    }
  }

  /**
   * Calculate age for a sample using its per-sample callable loci if available.
   */
  def calculateAgeForSample(
    haplogroupId: Int,
    sampleGuid: UUID,
    mutationRate: Double = DefaultMutationRate
  ): Future[Option[AgeEstimateResult]] = {
    for {
      callableLoci <- getCallableLociForSample(sampleGuid)
      result <- calculateAge(haplogroupId, callableLoci, mutationRate)
    } yield result
  }

  /**
   * Combine SNP and STR age estimates using inverse-variance weighting.
   *
   * P(t|all) proportional to P(t|SNPs) x P(t|STRs)
   * Approximated by inverse-variance weighted mean of the two point estimates.
   */
  private[services] def combineSnpAndStrEstimates(
    snpResult: AgeEstimateResult,
    strResult: StrAgeEstimateResult
  ): CombinedAgeEstimateResult = {
    val snpEst = snpResult.estimate
    val strEst = strResult.estimate

    // If either has zero age, use the other
    if (snpEst.ybp == 0 && strEst.ybp == 0) {
      return CombinedAgeEstimateResult(
        estimate = AgeEstimate(0, Some(0), Some(0)),
        snpEstimate = snpResult,
        strEstimate = Some(strResult),
        method = "COMBINED_SNP_STR"
      )
    }
    if (strEst.ybp == 0 || strResult.markerCount == 0) {
      return CombinedAgeEstimateResult(
        estimate = snpEst, snpEstimate = snpResult, strEstimate = Some(strResult),
        method = "SNP_ONLY"
      )
    }
    if (snpEst.ybp == 0 || snpResult.snpCount == 0) {
      return CombinedAgeEstimateResult(
        estimate = strEst, snpEstimate = snpResult, strEstimate = Some(strResult),
        method = "STR_ONLY"
      )
    }

    // Inverse-variance weighting
    // Variance approximated from CI width: sigma ≈ (upper - lower) / (2 * 1.96)
    val snpSigma = ciToSigma(snpEst)
    val strSigma = ciToSigma(strEst)

    if (snpSigma <= 0 || strSigma <= 0) {
      // Fallback to simple average if CI is degenerate
      val avg = (snpEst.ybp + strEst.ybp) / 2
      return CombinedAgeEstimateResult(
        estimate = AgeEstimate(avg, snpEst.ybpLower, snpEst.ybpUpper),
        snpEstimate = snpResult, strEstimate = Some(strResult),
        method = "COMBINED_SNP_STR"
      )
    }

    val snpWeight = 1.0 / (snpSigma * snpSigma)
    val strWeight = 1.0 / (strSigma * strSigma)
    val totalWeight = snpWeight + strWeight

    val combinedYbp = math.round((snpEst.ybp * snpWeight + strEst.ybp * strWeight) / totalWeight).toInt
    val combinedSigma = math.sqrt(1.0 / totalWeight)
    val combinedLower = math.max(0, math.round(combinedYbp - 1.96 * combinedSigma).toInt)
    val combinedUpper = math.round(combinedYbp + 1.96 * combinedSigma).toInt

    CombinedAgeEstimateResult(
      estimate = AgeEstimate(combinedYbp, Some(combinedLower), Some(combinedUpper)),
      snpEstimate = snpResult,
      strEstimate = Some(strResult),
      method = "COMBINED_SNP_STR"
    )
  }

  /**
   * Extract sigma from an AgeEstimate's confidence interval.
   */
  private def ciToSigma(est: AgeEstimate): Double = {
    (est.ybpUpper, est.ybpLower) match {
      case (Some(upper), Some(lower)) if upper > lower =>
        (upper - lower).toDouble / (2 * 1.96)
      case _ => 0.0
    }
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
 * Result of a combined SNP + STR age estimation.
 */
case class CombinedAgeEstimateResult(
  estimate: AgeEstimate,
  snpEstimate: AgeEstimateResult,
  strEstimate: Option[StrAgeEstimateResult],
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
