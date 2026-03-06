package services

import helpers.ServiceSpec
import models.domain.publications.PublicationCandidate
import org.mockito.Mockito.when
import play.api.Configuration
import play.api.libs.json.Json
import repositories.PublicationCandidateRepository

import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.Future

class ScoringFeedbackServiceSpec extends ServiceSpec {

  val mockCandidateRepo: PublicationCandidateRepository = mock[PublicationCandidateRepository]
  val config: Configuration = Configuration.from(Map.empty)
  val relevanceScoringService = new RelevanceScoringService(config)

  val service = new ScoringFeedbackService(mockCandidateRepo, relevanceScoringService)

  def makeCandidate(
    id: Int,
    status: String,
    title: String = "Test Publication",
    abstractText: Option[String] = None,
    journalName: Option[String] = None,
    rawMetadata: Option[String] = None
  ): PublicationCandidate =
    PublicationCandidate(
      id = Some(id), openAlexId = s"W$id", doi = Some(s"10.1234/test$id"),
      title = title, `abstract` = abstractText,
      publicationDate = Some(LocalDate.of(2025, 1, 1)),
      journalName = journalName, relevanceScore = Some(0.5),
      discoveryDate = LocalDateTime.now(), status = status,
      reviewedBy = None, reviewedAt = None, rejectionReason = None,
      rawMetadata = rawMetadata.map(Json.parse)
    )

  def acceptedGenomics(id: Int): PublicationCandidate = makeCandidate(
    id, "accepted",
    title = "Y-chromosome haplogroup phylogeny in ancient DNA",
    abstractText = Some("We analyzed Y-DNA SNP data using whole genome sequencing"),
    journalName = Some("Nature Genetics"),
    rawMetadata = Some("""{"concepts": [{"display_name": "Haplogroup", "score": 0.9}], "citation_normalized_percentile": {"value": 0.8}}""")
  )

  def rejectedIrrelevant(id: Int): PublicationCandidate = makeCandidate(
    id, "rejected",
    title = "Machine learning for financial market prediction",
    journalName = Some("Journal of Finance"),
    rawMetadata = Some("""{"concepts": [{"display_name": "Machine Learning", "score": 0.9}], "cited_by_count": 5}""")
  )

  "ScoringFeedbackService" should {

    "computeLearnedWeights" should {

      "return None with insufficient data" in {
        val fewCandidates = (1 to 5).map(i => acceptedGenomics(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(fewCandidates))

        whenReady(service.computeLearnedWeights()) { result =>
          result mustBe empty
        }
      }

      "return None when only accepted candidates exist" in {
        val candidates = (1 to 12).map(i => acceptedGenomics(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(candidates))

        whenReady(service.computeLearnedWeights()) { result =>
          result mustBe empty
        }
      }

      "return None when only rejected candidates exist" in {
        val candidates = (1 to 12).map(i => rejectedIrrelevant(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(candidates))

        whenReady(service.computeLearnedWeights()) { result =>
          result mustBe empty
        }
      }

      "return learned weights with sufficient mixed data" in {
        val accepted = (1 to 6).map(i => acceptedGenomics(i))
        val rejected = (7 to 12).map(i => rejectedIrrelevant(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(accepted ++ rejected))

        whenReady(service.computeLearnedWeights()) { result =>
          result mustBe defined
          val weights = result.get
          weights.sampleSize mustBe 12

          // Weights must sum to ~1.0
          val sum = weights.keywordWeight + weights.conceptWeight + weights.citationWeight + weights.journalWeight
          sum mustBe 1.0 +- 0.001

          // All weights must be positive
          weights.keywordWeight must be > 0.0
          weights.conceptWeight must be > 0.0
          weights.citationWeight must be > 0.0
          weights.journalWeight must be > 0.0
        }
      }

      "increase weight for discriminative components" in {
        val accepted = (1 to 6).map(i => acceptedGenomics(i))
        val rejected = (7 to 12).map(i => rejectedIrrelevant(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(accepted ++ rejected))

        whenReady(service.computeLearnedWeights()) { result =>
          val weights = result.get
          // Keyword should be highly discriminative (genomics terms vs finance terms)
          // so its weight should remain high or increase
          weights.discriminativePower("keyword") must be > 0.0
        }
      }
    }

    "analyzeFeedback" should {

      "return None when no reviewed candidates exist" in {
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(Seq.empty))

        whenReady(service.analyzeFeedback()) { result =>
          result mustBe empty
        }
      }

      "return analysis with reviewed candidates" in {
        val accepted = (1 to 3).map(i => acceptedGenomics(i))
        val rejected = (4 to 6).map(i => rejectedIrrelevant(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(accepted ++ rejected))

        whenReady(service.analyzeFeedback()) { result =>
          result mustBe defined
          val analysis = result.get
          analysis.totalReviewed mustBe 6
          analysis.acceptedCount mustBe 3
          analysis.rejectedCount mustBe 3
          analysis.acceptedMeans must contain key "keyword"
          analysis.rejectedMeans must contain key "keyword"
          analysis.componentDiscriminativePower must contain key "keyword"
        }
      }

      "show higher keyword mean for accepted genomics papers" in {
        val accepted = (1 to 3).map(i => acceptedGenomics(i))
        val rejected = (4 to 6).map(i => rejectedIrrelevant(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(accepted ++ rejected))

        whenReady(service.analyzeFeedback()) { result =>
          val analysis = result.get
          analysis.acceptedMeans("keyword") must be > analysis.rejectedMeans("keyword")
        }
      }
    }

    "computeMeans" should {

      "return zeros for empty breakdowns" in {
        val means = service.computeMeans(Seq.empty)
        means("keyword") mustBe 0.0
        means("concept") mustBe 0.0
        means("citation") mustBe 0.0
        means("journal") mustBe 0.0
      }

      "compute correct averages" in {
        val breakdowns = Seq(
          ScoringBreakdown(0.8, 0.6, 0.4, 1.0, 0.7, 0.35, 0.25, 0.20, 0.20),
          ScoringBreakdown(0.6, 0.4, 0.2, 0.3, 0.4, 0.35, 0.25, 0.20, 0.20)
        )
        val means = service.computeMeans(breakdowns)
        means("keyword") mustBe 0.7 +- 0.001
        means("concept") mustBe 0.5 +- 0.001
        means("citation") mustBe 0.3 +- 0.001
        means("journal") mustBe 0.65 +- 0.001
      }
    }

    "computeDiscriminativePower" should {

      "measure separation between accepted and rejected" in {
        val acceptedBreakdowns = Seq(
          ScoringBreakdown(0.9, 0.8, 0.5, 1.0, 0.8, 0.35, 0.25, 0.20, 0.20),
          ScoringBreakdown(0.8, 0.7, 0.6, 1.0, 0.75, 0.35, 0.25, 0.20, 0.20)
        )
        val rejectedBreakdowns = Seq(
          ScoringBreakdown(0.0, 0.0, 0.3, 0.3, 0.1, 0.35, 0.25, 0.20, 0.20),
          ScoringBreakdown(0.1, 0.1, 0.2, 0.3, 0.15, 0.35, 0.25, 0.20, 0.20)
        )

        val power = service.computeDiscriminativePower(acceptedBreakdowns, rejectedBreakdowns)
        // Keyword: |0.85 - 0.05| = 0.8
        power("keyword") mustBe 0.8 +- 0.001
        // Journal: |1.0 - 0.3| = 0.7
        power("journal") mustBe 0.7 +- 0.001
        // All should be positive
        power.values.foreach(_ must be >= 0.0)
      }
    }

    "deriveWeights" should {

      "produce weights that sum to 1.0" in {
        val accepted = (1 to 6).map(i => acceptedGenomics(i))
        val rejected = (7 to 12).map(i => rejectedIrrelevant(i))

        val weights = service.deriveWeights(accepted, rejected)
        val sum = weights.keywordWeight + weights.conceptWeight + weights.citationWeight + weights.journalWeight
        sum mustBe 1.0 +- 0.001
      }

      "preserve stability through blending with original weights" in {
        val accepted = (1 to 6).map(i => acceptedGenomics(i))
        val rejected = (7 to 12).map(i => rejectedIrrelevant(i))

        val weights = service.deriveWeights(accepted, rejected)
        // No single weight should dominate completely due to blending
        weights.keywordWeight must be < 0.9
        weights.conceptWeight must be < 0.9
        weights.citationWeight must be < 0.9
        weights.journalWeight must be < 0.9
      }
    }

    "integration with RelevanceScoringService" should {

      "apply learned weights to scoring service" in {
        val accepted = (1 to 6).map(i => acceptedGenomics(i))
        val rejected = (7 to 12).map(i => rejectedIrrelevant(i))
        when(mockCandidateRepo.listReviewed()).thenReturn(Future.successful(accepted ++ rejected))

        val (origKw, origCon, origCit, origJrn) = relevanceScoringService.getActiveWeights

        whenReady(service.computeLearnedWeights()) { result =>
          val weights = result.get
          relevanceScoringService.applyLearnedWeights(weights)

          val (newKw, newCon, newCit, newJrn) = relevanceScoringService.getActiveWeights
          // Weights should have changed from defaults
          (newKw, newCon, newCit, newJrn) must not be ((origKw, origCon, origCit, origJrn))
        }
      }

      "revert to defaults when cleared" in {
        relevanceScoringService.applyLearnedWeights(LearnedWeights(0.5, 0.2, 0.2, 0.1, 10, Map.empty))
        relevanceScoringService.getActiveWeights._1 mustBe 0.5

        relevanceScoringService.clearLearnedWeights()
        relevanceScoringService.getActiveWeights._1 mustBe 0.35
      }
    }
  }
}
