package services

import jakarta.inject.Inject
import models.domain.publications.PublicationCandidate
import play.api.libs.json.{JsArray, JsValue}
import play.api.{Configuration, Logging}

import scala.concurrent.ExecutionContext

/**
 * Calculates relevance scores for publication candidates using multiple signals:
 *
 * 1. Keyword matching — title/abstract matched against domain-relevant terms
 * 2. OpenAlex concept weighting — concepts with scores from OpenAlex taxonomy
 * 3. Citation impact — normalized citation percentile and raw count
 * 4. Journal relevance — bonus for known high-value journals
 *
 * Final score is a weighted combination normalized to [0, 1].
 */
class RelevanceScoringService @Inject()(
  configuration: Configuration
)(implicit ec: ExecutionContext) extends Logging {

  // Weights for each scoring component (should sum to 1.0)
  private val keywordWeight: Double = configuration.getOptional[Double]("publication-discovery.scoring.keywordWeight").getOrElse(0.35)
  private val conceptWeight: Double = configuration.getOptional[Double]("publication-discovery.scoring.conceptWeight").getOrElse(0.25)
  private val citationWeight: Double = configuration.getOptional[Double]("publication-discovery.scoring.citationWeight").getOrElse(0.20)
  private val journalWeight: Double = configuration.getOptional[Double]("publication-discovery.scoring.journalWeight").getOrElse(0.20)

  // High-value keywords for genomics/phylogenetics domain
  private[services] val primaryKeywords: Set[String] = Set(
    "haplogroup", "y-dna", "y-chromosome", "mtdna", "mitochondrial dna",
    "phylogenetic", "phylogeny", "ancient dna", "adna",
    "y-str", "snp", "whole genome sequencing", "population genetics",
    "human migration", "coalescent", "tmrca", "molecular clock"
  )

  private[services] val secondaryKeywords: Set[String] = Set(
    "genetic genealogy", "paternal lineage", "maternal lineage",
    "uniparental", "non-recombining", "nry",
    "demographic history", "founder effect", "genetic drift",
    "admixture", "archaeogenetics", "paleogenomics",
    "short tandem repeat", "microsatellite"
  )

  // OpenAlex concepts that indicate high relevance
  private[services] val highValueConcepts: Set[String] = Set(
    "haplogroup", "y chromosome", "mitochondrial dna", "human y-chromosome dna haplogroup",
    "phylogenetics", "ancient dna", "population genetics",
    "genetic genealogy", "molecular phylogenetics"
  )

  private[services] val mediumValueConcepts: Set[String] = Set(
    "genetics", "genomics", "human genetics", "molecular biology",
    "single-nucleotide polymorphism", "dna sequencing",
    "biological anthropology", "archaeogenetics"
  )

  // Known high-value journals for this domain
  private[services] val highValueJournals: Set[String] = Set(
    "nature", "science", "nature genetics", "nature communications",
    "molecular biology and evolution", "american journal of human genetics",
    "european journal of human genetics", "genome research",
    "human genetics", "human mutation", "genome biology",
    "plos genetics", "current biology", "cell",
    "proceedings of the national academy of sciences",
    "annals of human genetics", "genes", "forensic science international: genetics"
  ).map(_.toLowerCase)

  /**
   * Calculate the composite relevance score for a candidate.
   */
  def score(candidate: PublicationCandidate): Double = {
    val keywordScore = calculateKeywordScore(candidate)
    val conceptScore = calculateConceptScore(candidate.rawMetadata)
    val citationScore = calculateCitationScore(candidate.rawMetadata)
    val journalScore = calculateJournalScore(candidate.journalName)

    val composite = keywordWeight * keywordScore +
      conceptWeight * conceptScore +
      citationWeight * citationScore +
      journalWeight * journalScore

    // Clamp to [0, 1]
    math.max(0.0, math.min(1.0, composite))
  }

  /**
   * Score a batch of candidates, returning them with updated relevance scores.
   */
  def scoreCandidates(candidates: Seq[PublicationCandidate]): Seq[PublicationCandidate] = {
    candidates.map { c =>
      val newScore = score(c)
      c.copy(relevanceScore = Some(newScore))
    }
  }

  /**
   * Keyword-based scoring: check title and abstract for domain-relevant terms.
   * Primary keywords score higher than secondary keywords.
   */
  private[services] def calculateKeywordScore(candidate: PublicationCandidate): Double = {
    val text = (candidate.title + " " + candidate.`abstract`.getOrElse("")).toLowerCase

    val primaryHits = primaryKeywords.count(kw => text.contains(kw))
    val secondaryHits = secondaryKeywords.count(kw => text.contains(kw))

    // Each primary keyword contributes 0.15, each secondary 0.08, capped at 1.0
    val rawScore = primaryHits * 0.15 + secondaryHits * 0.08
    math.min(1.0, rawScore)
  }

  /**
   * OpenAlex concept-based scoring: extract concepts from raw metadata
   * and weight by concept relevance and OpenAlex-assigned score.
   *
   * OpenAlex concepts have structure: [{display_name: "...", score: 0.8, ...}, ...]
   */
  private[services] def calculateConceptScore(rawMetadata: Option[JsValue]): Double = {
    rawMetadata.flatMap { json =>
      // Try both "concepts" (older API) and "topics" (newer API)
      val concepts = (json \ "concepts").asOpt[JsArray]
        .orElse((json \ "topics").asOpt[JsArray])
        .map(_.value.toSeq)
        .getOrElse(Seq.empty)

      if (concepts.isEmpty) None
      else {
        var totalScore = 0.0

        for (concept <- concepts) {
          val name = (concept \ "display_name").asOpt[String].getOrElse("").toLowerCase
          val apiScore = (concept \ "score").asOpt[Double].getOrElse(0.0)

          if (highValueConcepts.exists(hvc => name.contains(hvc))) {
            totalScore += apiScore * 1.0 // Full weight for high-value
          } else if (mediumValueConcepts.exists(mvc => name.contains(mvc))) {
            totalScore += apiScore * 0.4 // Reduced weight for medium-value
          }
        }

        Some(math.min(1.0, totalScore))
      }
    }.getOrElse(0.0)
  }

  /**
   * Citation-based scoring using OpenAlex citation metrics.
   *
   * Uses citation_normalized_percentile (0-1) if available,
   * otherwise falls back to cited_by_count with logarithmic scaling.
   */
  private[services] def calculateCitationScore(rawMetadata: Option[JsValue]): Double = {
    rawMetadata.flatMap { json =>
      // Prefer normalized percentile (already 0-1)
      val percentile = (json \ "citation_normalized_percentile" \ "value").asOpt[Double]
        .orElse((json \ "cited_by_percentile_year" \ "max").asOpt[Double].map(_ / 100.0))

      percentile.orElse {
        // Fallback: logarithmic scaling of raw citation count
        (json \ "cited_by_count").asOpt[Int].map { count =>
          if (count <= 0) 0.0
          else math.min(1.0, math.log10(count.toDouble + 1) / 3.0) // log10(1001)/3 ≈ 1.0
        }
      }
    }.getOrElse(0.0)
  }

  /**
   * Journal-based scoring: bonus for publications in known high-value journals.
   */
  private[services] def calculateJournalScore(journalName: Option[String]): Double = {
    journalName.map(_.toLowerCase) match {
      case Some(name) if highValueJournals.exists(j => name.contains(j)) => 1.0
      case Some(_) => 0.3 // Known journal, but not high-value
      case None => 0.0
    }
  }

  /**
   * Get a breakdown of scoring components for debugging/display.
   */
  def scoreBreakdown(candidate: PublicationCandidate): ScoringBreakdown = {
    ScoringBreakdown(
      keywordScore = calculateKeywordScore(candidate),
      conceptScore = calculateConceptScore(candidate.rawMetadata),
      citationScore = calculateCitationScore(candidate.rawMetadata),
      journalScore = calculateJournalScore(candidate.journalName),
      compositeScore = score(candidate),
      keywordWeight = keywordWeight,
      conceptWeight = conceptWeight,
      citationWeight = citationWeight,
      journalWeight = journalWeight
    )
  }
}

case class ScoringBreakdown(
  keywordScore: Double,
  conceptScore: Double,
  citationScore: Double,
  journalScore: Double,
  compositeScore: Double,
  keywordWeight: Double,
  conceptWeight: Double,
  citationWeight: Double,
  journalWeight: Double
)
