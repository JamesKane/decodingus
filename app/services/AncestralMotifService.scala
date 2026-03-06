package services

import jakarta.inject.Inject
import models.domain.haplogroups.{HaplogroupAncestralStr, MotifMethod}
import play.api.Logging
import repositories.{BiosampleVariantCallRepository, HaplogroupAncestralStrRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Computes and manages ancestral STR motifs (modal haplotypes) for haplogroups.
 *
 * The modal haplotype is the most common STR repeat count across all samples
 * assigned to a haplogroup. It represents the ancestral state for that branch,
 * used in STR-based age estimation.
 *
 * Methods:
 * - MODAL: Simple mode (most frequent value) across samples
 * - PHYLOGENETIC: Inferred from ASR across the tree (future)
 * - MANUAL: Curator-set values for well-studied haplogroups
 */
class AncestralMotifService @Inject()(
  ancestralStrRepo: HaplogroupAncestralStrRepository,
  variantCallRepo: BiosampleVariantCallRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Get the ancestral motif for a haplogroup.
   *
   * @return Map of markerName -> ancestral repeat count
   */
  def getMotifForHaplogroup(haplogroupId: Int): Future[Map[String, Int]] = {
    ancestralStrRepo.findByHaplogroup(haplogroupId).map { motifs =>
      motifs.flatMap(m => m.ancestralValue.map(v => m.markerName -> v)).toMap
    }
  }

  /**
   * Compute modal haplotype from a set of sample STR observations.
   *
   * For each marker, finds the mode (most common value) across all samples.
   *
   * @param observations Seq of (markerName, observedValue) pairs from multiple samples
   * @param haplogroupId Target haplogroup
   * @return Computed ancestral STR motifs ready for persistence
   */
  private[services] def computeModalHaplotype(
    observations: Seq[MarkerObservation],
    haplogroupId: Int
  ): Seq[HaplogroupAncestralStr] = {
    val now = LocalDateTime.now()

    // Group by marker name
    val byMarker = observations.groupBy(_.markerName)

    byMarker.map { case (markerName, obs) =>
      val values = obs.map(_.value)
      val sampleCount = values.size

      // Compute mode
      val valueCounts = values.groupBy(identity).view.mapValues(_.size).toMap
      val mode = valueCounts.maxBy(_._2)._1
      val modeCount = valueCounts(mode)

      // Compute confidence: fraction of samples agreeing with mode
      val confidence = if (sampleCount > 0) BigDecimal(modeCount.toDouble / sampleCount) else BigDecimal(0)

      // Compute variance
      val mean = values.sum.toDouble / sampleCount
      val variance = if (sampleCount > 1) {
        BigDecimal(values.map(v => math.pow(v - mean, 2)).sum / (sampleCount - 1))
      } else BigDecimal(0)

      // Find alternative modal values (any value with count >= 2 that isn't the mode)
      val alternatives = valueCounts
        .filter { case (v, count) => v != mode && count >= 2 }
        .keys.toList.sorted

      HaplogroupAncestralStr(
        haplogroupId = haplogroupId,
        markerName = markerName,
        ancestralValue = Some(mode),
        ancestralValueAlt = if (alternatives.nonEmpty) Some(alternatives) else None,
        confidence = Some(confidence),
        supportingSamples = Some(sampleCount),
        variance = Some(variance),
        computedAt = now,
        method = MotifMethod.Modal
      )
    }.toSeq
  }

  /**
   * Compute and save the modal haplotype for a haplogroup from sample observations.
   *
   * @param observations Collected STR observations from samples in this haplogroup
   * @param haplogroupId Target haplogroup
   * @return Number of markers computed
   */
  def computeAndSaveMotif(
    observations: Seq[MarkerObservation],
    haplogroupId: Int
  ): Future[Int] = {
    val motifs = computeModalHaplotype(observations, haplogroupId)
    if (motifs.isEmpty) {
      Future.successful(0)
    } else {
      ancestralStrRepo.upsertBatch(motifs).map(_.size)
    }
  }

  /**
   * Manually set an ancestral STR value for a specific marker.
   */
  def setManualMotif(
    haplogroupId: Int,
    markerName: String,
    value: Int,
    confidence: Option[BigDecimal] = None
  ): Future[Int] = {
    val motif = HaplogroupAncestralStr(
      haplogroupId = haplogroupId,
      markerName = markerName,
      ancestralValue = Some(value),
      ancestralValueAlt = None,
      confidence = confidence,
      supportingSamples = None,
      variance = None,
      method = MotifMethod.Manual
    )
    ancestralStrRepo.upsert(motif)
  }

  /**
   * Delete all computed motifs for a haplogroup (e.g., before recomputation).
   */
  def clearMotifs(haplogroupId: Int): Future[Int] =
    ancestralStrRepo.deleteByHaplogroup(haplogroupId)
}

/**
 * A single STR marker observation from a sample.
 */
case class MarkerObservation(
  markerName: String,
  value: Int,
  biosampleId: Int
)
