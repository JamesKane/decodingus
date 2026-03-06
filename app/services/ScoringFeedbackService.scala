package services

import jakarta.inject.{Inject, Singleton}
import models.domain.publications.PublicationCandidate
import play.api.Logging
import repositories.PublicationCandidateRepository

import scala.concurrent.{ExecutionContext, Future}

/**
 * Learns from curator accept/reject decisions to improve relevance scoring.
 *
 * Analyzes historical decisions by computing per-component score distributions
 * for accepted vs rejected candidates, then derives adjusted weights that
 * emphasize components with higher discriminative power.
 */
@Singleton
class ScoringFeedbackService @Inject()(
  candidateRepository: PublicationCandidateRepository,
  relevanceScoringService: RelevanceScoringService
)(implicit ec: ExecutionContext) extends Logging {

  val MinSamplesForFeedback: Int = 10

  /**
   * Analyze all reviewed candidates and compute learned weight adjustments.
   * Returns None if insufficient data (< MinSamplesForFeedback reviewed candidates).
   */
  def computeLearnedWeights(): Future[Option[LearnedWeights]] = {
    candidateRepository.listReviewed().map { reviewed =>
      if (reviewed.size < MinSamplesForFeedback) {
        logger.info(s"Insufficient reviewed candidates (${reviewed.size}/$MinSamplesForFeedback) for feedback learning.")
        None
      } else {
        val accepted = reviewed.filter(_.status == "accepted")
        val rejected = reviewed.filter(_.status == "rejected")

        if (accepted.isEmpty || rejected.isEmpty) {
          logger.info("Need both accepted and rejected candidates for feedback learning.")
          None
        } else {
          Some(deriveWeights(accepted, rejected))
        }
      }
    }
  }

  /**
   * Compute a feedback analysis report with per-component statistics.
   */
  def analyzeFeedback(): Future[Option[FeedbackAnalysis]] = {
    candidateRepository.listReviewed().map { reviewed =>
      val accepted = reviewed.filter(_.status == "accepted")
      val rejected = reviewed.filter(_.status == "rejected")

      if (accepted.isEmpty && rejected.isEmpty) None
      else {
        val acceptedBreakdowns = accepted.map(relevanceScoringService.scoreBreakdown)
        val rejectedBreakdowns = rejected.map(relevanceScoringService.scoreBreakdown)

        Some(FeedbackAnalysis(
          totalReviewed = reviewed.size,
          acceptedCount = accepted.size,
          rejectedCount = rejected.size,
          acceptedMeans = computeMeans(acceptedBreakdowns),
          rejectedMeans = computeMeans(rejectedBreakdowns),
          componentDiscriminativePower = computeDiscriminativePower(acceptedBreakdowns, rejectedBreakdowns)
        ))
      }
    }
  }

  private[services] def deriveWeights(
    accepted: Seq[PublicationCandidate],
    rejected: Seq[PublicationCandidate]
  ): LearnedWeights = {
    val acceptedBreakdowns = accepted.map(relevanceScoringService.scoreBreakdown)
    val rejectedBreakdowns = rejected.map(relevanceScoringService.scoreBreakdown)

    val discriminativePower = computeDiscriminativePower(acceptedBreakdowns, rejectedBreakdowns)

    // Compute new weights proportional to discriminative power,
    // blended with original weights for stability (70% original, 30% learned)
    val blendRatio = 0.3
    val originalWeights = Map(
      "keyword" -> relevanceScoringService.scoreBreakdown(accepted.head).keywordWeight,
      "concept" -> relevanceScoringService.scoreBreakdown(accepted.head).conceptWeight,
      "citation" -> relevanceScoringService.scoreBreakdown(accepted.head).citationWeight,
      "journal" -> relevanceScoringService.scoreBreakdown(accepted.head).journalWeight
    )

    // Normalize discriminative power to sum to 1.0 for use as weights
    val totalPower = discriminativePower.values.sum
    val learnedRaw = if (totalPower > 0) {
      discriminativePower.view.mapValues(_ / totalPower).toMap
    } else {
      originalWeights
    }

    // Blend: new_weight = (1 - blend) * original + blend * learned
    val blended = originalWeights.map { case (component, origWeight) =>
      val learnedWeight = learnedRaw.getOrElse(component, origWeight)
      component -> ((1.0 - blendRatio) * origWeight + blendRatio * learnedWeight)
    }

    // Normalize blended weights to sum to 1.0
    val blendedTotal = blended.values.sum
    val normalized = blended.view.mapValues(_ / blendedTotal).toMap

    logger.info(s"Learned weights from ${accepted.size + rejected.size} reviewed candidates: $normalized")

    LearnedWeights(
      keywordWeight = normalized("keyword"),
      conceptWeight = normalized("concept"),
      citationWeight = normalized("citation"),
      journalWeight = normalized("journal"),
      sampleSize = accepted.size + rejected.size,
      discriminativePower = discriminativePower
    )
  }

  /**
   * Discriminative power = |mean_accepted - mean_rejected| for each component.
   * Higher values mean the component better separates accepted from rejected.
   */
  private[services] def computeDiscriminativePower(
    acceptedBreakdowns: Seq[ScoringBreakdown],
    rejectedBreakdowns: Seq[ScoringBreakdown]
  ): Map[String, Double] = {
    val acceptedMeans = computeMeans(acceptedBreakdowns)
    val rejectedMeans = computeMeans(rejectedBreakdowns)

    Map(
      "keyword" -> math.abs(acceptedMeans.getOrElse("keyword", 0.0) - rejectedMeans.getOrElse("keyword", 0.0)),
      "concept" -> math.abs(acceptedMeans.getOrElse("concept", 0.0) - rejectedMeans.getOrElse("concept", 0.0)),
      "citation" -> math.abs(acceptedMeans.getOrElse("citation", 0.0) - rejectedMeans.getOrElse("citation", 0.0)),
      "journal" -> math.abs(acceptedMeans.getOrElse("journal", 0.0) - rejectedMeans.getOrElse("journal", 0.0))
    )
  }

  private[services] def computeMeans(breakdowns: Seq[ScoringBreakdown]): Map[String, Double] = {
    if (breakdowns.isEmpty) return Map("keyword" -> 0.0, "concept" -> 0.0, "citation" -> 0.0, "journal" -> 0.0)

    val n = breakdowns.size.toDouble
    Map(
      "keyword" -> breakdowns.map(_.keywordScore).sum / n,
      "concept" -> breakdowns.map(_.conceptScore).sum / n,
      "citation" -> breakdowns.map(_.citationScore).sum / n,
      "journal" -> breakdowns.map(_.journalScore).sum / n
    )
  }
}

case class LearnedWeights(
  keywordWeight: Double,
  conceptWeight: Double,
  citationWeight: Double,
  journalWeight: Double,
  sampleSize: Int,
  discriminativePower: Map[String, Double]
)

case class FeedbackAnalysis(
  totalReviewed: Int,
  acceptedCount: Int,
  rejectedCount: Int,
  acceptedMeans: Map[String, Double],
  rejectedMeans: Map[String, Double],
  componentDiscriminativePower: Map[String, Double]
)
