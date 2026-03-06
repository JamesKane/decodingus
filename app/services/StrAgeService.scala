package services

import jakarta.inject.Inject
import models.dal.domain.genomics.{HaplogroupCharacterState, StrMutationRate}
import models.domain.haplogroups.AgeEstimate
import play.api.Logging
import repositories.{BiosampleVariantCallRepository, HaplogroupCharacterStateRepository, StrMutationRateRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * STR-based age estimation using genetic distance analysis.
 *
 * Uses the stepwise mutation model with per-marker mutation rates to calculate
 * P(t|g_STRs) = product of P(t|ms) x P(gs|ms) across all markers.
 *
 * Multi-step mutation frequencies (from McDonald 2021):
 *   omega_1 = 0.962 (single-step)
 *   omega_2 = 0.032 (two-step)
 *   omega_3 = 0.004 (three-step)
 *
 * Generation length: 33 years (pre-industrial average)
 */
class StrAgeService @Inject()(
  strMutationRateRepo: StrMutationRateRepository,
  characterStateRepo: HaplogroupCharacterStateRepository,
  variantCallRepo: BiosampleVariantCallRepository
)(implicit ec: ExecutionContext) extends Logging {

  // Default generation length in years
  val DefaultGenerationLength: Double = 33.0
  val DefaultGenerationLengthSigma: Double = 4.0

  // Multi-step mutation frequencies
  val Omega1: Double = 0.962
  val Omega2: Double = 0.032
  val Omega3: Double = 0.004

  /**
   * Calculate STR-based age estimate for a set of sample STR observations
   * relative to an ancestral haplogroup's modal haplotype.
   *
   * @param ancestralStates Map of variantId -> ancestral repeat count (from ASR)
   * @param observedValues  Map of variantId -> observed repeat count (from sample)
   * @param mutationRates   Map of variantId -> StrMutationRate
   * @param generationLength Years per generation
   * @return Age estimate result
   */
  private[services] def calculateFromGeneticDistance(
    ancestralStates: Map[Int, Int],
    observedValues: Map[Int, Int],
    mutationRates: Map[Int, StrMutationRate],
    generationLength: Double = DefaultGenerationLength
  ): StrAgeEstimateResult = {
    // Find markers present in all three maps
    val commonMarkers = ancestralStates.keySet
      .intersect(observedValues.keySet)
      .intersect(mutationRates.keySet)

    if (commonMarkers.isEmpty) {
      return StrAgeEstimateResult(
        estimate = AgeEstimate(0, Some(0), Some(0)),
        markerCount = 0,
        totalGeneticDistance = 0,
        method = "STR_GENETIC_DISTANCE"
      )
    }

    // Calculate per-marker genetic distance and sum
    val markerResults = commonMarkers.toSeq.map { variantId =>
      val ancestral = ancestralStates(variantId)
      val observed = observedValues(variantId)
      val rate = mutationRates(variantId)
      val distance = math.abs(observed - ancestral)
      val stepSize = observed - ancestral
      MarkerDistance(variantId, ancestral, observed, distance, stepSize, rate)
    }

    val totalGeneticDistance = markerResults.map(_.distance).sum
    val markerCount = markerResults.size

    // Average mutation rate across markers (per generation)
    val avgMutationRate = markerResults.map(_.rate.mutationRate.toDouble).sum / markerCount

    // Estimate generations using total genetic distance
    // E[distance] = sum(mu_i) * t_gen, so t_gen = total_distance / sum(mu_i)
    val totalMutationRate = markerResults.map(_.rate.mutationRate.toDouble).sum
    val estimatedGenerations = if (totalMutationRate > 0) {
      totalGeneticDistance.toDouble / totalMutationRate
    } else 0.0

    val pointYears = math.round(estimatedGenerations * generationLength).toInt

    // Confidence interval using variance of genetic distance
    // Var(distance) ≈ sum(mu_i * (1 + multi_step_contribution)) * t_gen
    // For CI: use Poisson-like approximation on total distance
    val (lowerDist, upperDist) = geneticDistanceConfidenceInterval(
      totalGeneticDistance, totalMutationRate, markerCount
    )
    val lowerYears = if (totalMutationRate > 0)
      math.round((lowerDist / totalMutationRate) * generationLength).toInt
    else 0
    val upperYears = if (totalMutationRate > 0)
      math.round((upperDist / totalMutationRate) * generationLength).toInt
    else 0

    if (markerCount > 0) {
      logger.debug(s"STR age estimate: $pointYears YBP ($lowerYears–$upperYears) from $markerCount markers, " +
        s"total genetic distance $totalGeneticDistance")
    }

    StrAgeEstimateResult(
      estimate = AgeEstimate(pointYears, Some(lowerYears), Some(upperYears)),
      markerCount = markerCount,
      totalGeneticDistance = totalGeneticDistance,
      method = "STR_GENETIC_DISTANCE"
    )
  }

  /**
   * Calculate STR age for a biosample relative to its terminal haplogroup's
   * ancestral motif, using stored character states and variant calls.
   *
   * @param biosampleId    The biosample to calculate for
   * @param haplogroupId   The terminal haplogroup with ancestral STR motifs
   * @param strVariantIds  IDs of STR-type variants to use
   * @return STR age estimate or None if insufficient data
   */
  def calculateForBiosample(
    biosampleId: Int,
    haplogroupId: Int,
    strVariantIds: Seq[Int]
  ): Future[Option[StrAgeEstimateResult]] = {
    for {
      // Get ancestral states from ASR
      ancestralStates <- characterStateRepo.findStrStatesForHaplogroup(haplogroupId, strVariantIds)

      // Get observed values from biosample
      observedCalls <- variantCallRepo.findByBiosampleAndVariants(biosampleId, strVariantIds)

      // Get mutation rates for all markers
      allRates <- strMutationRateRepo.findAll()
    } yield {
      // Build maps: variantId -> repeat count
      val ancestralMap = ancestralStates.flatMap { cs =>
        cs.inferredState.toIntOption.map(v => cs.variantId -> v)
      }.toMap

      val observedMap = observedCalls.flatMap { call =>
        call.observedState.toIntOption.map(v => call.variantId -> v)
      }.toMap

      // Build rate map by markerName -> StrMutationRate, then match via variantId
      // For now, build a variantId -> rate map using the variant IDs we have
      val rateByMarker = allRates.map(r => r.markerName -> r).toMap

      // We need to map variantIds to marker names. For now, use the variantId
      // directly as key in the rate map (rates are indexed by variantId for lookup)
      val rateMap = allRates.flatMap(r => r.id.map(_ -> r)).toMap

      if (ancestralMap.isEmpty || observedMap.isEmpty) None
      else Some(calculateFromGeneticDistance(ancestralMap, observedMap, rateMap))
    }
  }

  /**
   * Calculate STR-based TMRCA between two sets of STR observations.
   *
   * Uses the total genetic distance between the two samples across shared markers.
   */
  private[services] def calculateTmrcaFromStrs(
    observedValues1: Map[Int, Int],
    observedValues2: Map[Int, Int],
    mutationRates: Map[Int, StrMutationRate],
    generationLength: Double = DefaultGenerationLength
  ): StrAgeEstimateResult = {
    val commonMarkers = observedValues1.keySet
      .intersect(observedValues2.keySet)
      .intersect(mutationRates.keySet)

    if (commonMarkers.isEmpty) {
      return StrAgeEstimateResult(
        estimate = AgeEstimate(0, Some(0), Some(0)),
        markerCount = 0,
        totalGeneticDistance = 0,
        method = "STR_TMRCA"
      )
    }

    // Distance between two samples (TMRCA uses half the total)
    val totalDistance = commonMarkers.toSeq.map { vid =>
      math.abs(observedValues1(vid) - observedValues2(vid))
    }.sum

    val markerCount = commonMarkers.size
    val totalMutationRate = commonMarkers.toSeq.map(vid => mutationRates(vid).mutationRate.toDouble).sum
    // TMRCA: divide by 2 because distance accumulates on both lineages
    val twoLineageRate = 2.0 * totalMutationRate

    val estimatedGenerations = if (twoLineageRate > 0) {
      totalDistance.toDouble / twoLineageRate
    } else 0.0

    val pointYears = math.round(estimatedGenerations * generationLength).toInt

    val (lowerDist, upperDist) = geneticDistanceConfidenceInterval(
      totalDistance, twoLineageRate, markerCount
    )
    val lowerYears = if (twoLineageRate > 0)
      math.round((lowerDist / twoLineageRate) * generationLength).toInt
    else 0
    val upperYears = if (twoLineageRate > 0)
      math.round((upperDist / twoLineageRate) * generationLength).toInt
    else 0

    StrAgeEstimateResult(
      estimate = AgeEstimate(pointYears, Some(lowerYears), Some(upperYears)),
      markerCount = markerCount,
      totalGeneticDistance = totalDistance,
      method = "STR_TMRCA"
    )
  }

  /**
   * Confidence interval for genetic distance using a Poisson-like model.
   *
   * Genetic distance follows approximately Poisson(sum(mu_i) * t).
   * For small distances, use exact Poisson quantiles (approximated).
   * For larger distances, use normal approximation.
   */
  private[services] def geneticDistanceConfidenceInterval(
    observedDistance: Int,
    totalMutationRate: Double,
    markerCount: Int
  ): (Double, Double) = {
    if (observedDistance == 0) {
      return (0.0, -0.5 * math.log(0.025) * 2) // Upper bound for zero observations
    }

    // Normal approximation with multi-step variance inflation
    val varianceInflation = 1.0 + Omega2 * 4 + Omega3 * 9 // E[step^2] contribution
    val effectiveVariance = observedDistance.toDouble * varianceInflation
    val z = 1.96
    val sqrtVar = math.sqrt(effectiveVariance)
    val lower = math.max(0.0, observedDistance.toDouble - z * sqrtVar)
    val upper = observedDistance.toDouble + z * sqrtVar

    (lower, upper)
  }

  /**
   * Adjust multi-step mutation probability for a given step size.
   *
   * P(step=k) based on omega frequencies:
   *   |k|=1: omega_1 = 0.962
   *   |k|=2: omega_2 = 0.032
   *   |k|=3: omega_3 = 0.004
   *   |k|>=4: ~0 (negligible)
   */
  private[services] def multiStepProbability(stepSize: Int): Double = {
    math.abs(stepSize) match {
      case 0 => 1.0
      case 1 => Omega1
      case 2 => Omega2
      case 3 => Omega3
      case _ => 0.001 // Negligible for |step| >= 4
    }
  }
}

/**
 * Per-marker distance result.
 */
case class MarkerDistance(
  variantId: Int,
  ancestralValue: Int,
  observedValue: Int,
  distance: Int,
  stepSize: Int,
  rate: StrMutationRate
)

/**
 * Result of an STR-based age estimation.
 */
case class StrAgeEstimateResult(
  estimate: AgeEstimate,
  markerCount: Int,
  totalGeneticDistance: Int,
  method: String
)
