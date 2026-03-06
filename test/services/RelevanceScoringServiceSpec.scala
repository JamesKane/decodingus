package services

import helpers.ServiceSpec
import models.domain.publications.PublicationCandidate
import play.api.Configuration
import play.api.libs.json.Json

import java.time.{LocalDate, LocalDateTime}

class RelevanceScoringServiceSpec extends ServiceSpec {

  val config: Configuration = Configuration.from(Map.empty)
  val service = new RelevanceScoringService(config)

  def makeCandidate(
    title: String = "Test Publication",
    abstractText: Option[String] = None,
    journalName: Option[String] = None,
    rawMetadata: Option[String] = None
  ): PublicationCandidate =
    PublicationCandidate(
      id = Some(1), openAlexId = "W1234", doi = Some("10.1234/test"),
      title = title, `abstract` = abstractText,
      publicationDate = Some(LocalDate.of(2025, 1, 1)),
      journalName = journalName, relevanceScore = None,
      discoveryDate = LocalDateTime.now(), status = "pending",
      reviewedBy = None, reviewedAt = None, rejectionReason = None,
      rawMetadata = rawMetadata.map(Json.parse)
    )

  "RelevanceScoringService" should {

    "calculateKeywordScore" should {

      "score high for primary keywords in title" in {
        val candidate = makeCandidate(title = "Y-chromosome haplogroup phylogeny in ancient DNA samples")
        val score = service.calculateKeywordScore(candidate)
        score must be >= 0.3 // Multiple primary keyword hits
      }

      "score for keywords in abstract" in {
        val candidate = makeCandidate(
          title = "A genetic study",
          abstractText = Some("We analyzed Y-DNA haplogroup distributions using whole genome sequencing")
        )
        val score = service.calculateKeywordScore(candidate)
        score must be > 0.0
      }

      "score zero for unrelated content" in {
        val candidate = makeCandidate(title = "Machine learning in financial markets")
        val score = service.calculateKeywordScore(candidate)
        score mustBe 0.0
      }

      "include secondary keywords at lower weight" in {
        val primary = makeCandidate(title = "Y-chromosome haplogroup analysis")
        val secondary = makeCandidate(title = "Genetic genealogy and paternal lineage studies")

        val primaryScore = service.calculateKeywordScore(primary)
        val secondaryScore = service.calculateKeywordScore(secondary)

        primaryScore must be > secondaryScore
      }

      "cap at 1.0 for many keyword matches" in {
        val candidate = makeCandidate(
          title = "Y-DNA haplogroup phylogenetic analysis of ancient DNA using SNP and Y-STR",
          abstractText = Some("Population genetics study using whole genome sequencing with TMRCA molecular clock")
        )
        val score = service.calculateKeywordScore(candidate)
        score mustBe 1.0
      }
    }

    "calculateConceptScore" should {

      "score high for high-value concepts" in {
        val metadata = """{"concepts": [
          {"display_name": "Haplogroup", "score": 0.9},
          {"display_name": "Y chromosome", "score": 0.85}
        ]}"""
        val score = service.calculateConceptScore(Some(Json.parse(metadata)))
        score must be >= 0.5
      }

      "score lower for medium-value concepts" in {
        val metadata = """{"concepts": [
          {"display_name": "Genetics", "score": 0.9},
          {"display_name": "Genomics", "score": 0.8}
        ]}"""
        val score = service.calculateConceptScore(Some(Json.parse(metadata)))
        score must be > 0.0
        score must be < 1.0
      }

      "return zero for irrelevant concepts" in {
        val metadata = """{"concepts": [
          {"display_name": "Computer Science", "score": 0.9},
          {"display_name": "Machine Learning", "score": 0.8}
        ]}"""
        val score = service.calculateConceptScore(Some(Json.parse(metadata)))
        score mustBe 0.0
      }

      "return zero for missing metadata" in {
        service.calculateConceptScore(None) mustBe 0.0
      }

      "handle topics field as fallback" in {
        val metadata = """{"topics": [
          {"display_name": "Population Genetics", "score": 0.7}
        ]}"""
        val score = service.calculateConceptScore(Some(Json.parse(metadata)))
        score must be > 0.0
      }
    }

    "calculateCitationScore" should {

      "use normalized percentile when available" in {
        val metadata = """{"citation_normalized_percentile": {"value": 0.85}}"""
        val score = service.calculateCitationScore(Some(Json.parse(metadata)))
        score mustBe 0.85
      }

      "fall back to cited_by_count with log scaling" in {
        val metadata = """{"cited_by_count": 100}"""
        val score = service.calculateCitationScore(Some(Json.parse(metadata)))
        score must be > 0.0
        score must be < 1.0
      }

      "return zero for uncited papers" in {
        val metadata = """{"cited_by_count": 0}"""
        val score = service.calculateCitationScore(Some(Json.parse(metadata)))
        score mustBe 0.0
      }

      "return zero for missing metadata" in {
        service.calculateCitationScore(None) mustBe 0.0
      }

      "cap at 1.0 for highly cited papers" in {
        val metadata = """{"cited_by_count": 5000}"""
        val score = service.calculateCitationScore(Some(Json.parse(metadata)))
        score mustBe 1.0
      }
    }

    "calculateJournalScore" should {

      "score 1.0 for high-value journals" in {
        service.calculateJournalScore(Some("Nature Genetics")) mustBe 1.0
        service.calculateJournalScore(Some("Molecular Biology and Evolution")) mustBe 1.0
        service.calculateJournalScore(Some("American Journal of Human Genetics")) mustBe 1.0
      }

      "score 0.3 for other known journals" in {
        service.calculateJournalScore(Some("BMC Genomics")) mustBe 0.3
      }

      "score 0.0 for missing journal" in {
        service.calculateJournalScore(None) mustBe 0.0
      }

      "be case insensitive" in {
        service.calculateJournalScore(Some("NATURE GENETICS")) mustBe 1.0
        service.calculateJournalScore(Some("nature genetics")) mustBe 1.0
      }
    }

    "score (composite)" should {

      "produce high score for highly relevant paper" in {
        val metadata = """{
          "concepts": [{"display_name": "Haplogroup", "score": 0.9}, {"display_name": "Y chromosome", "score": 0.8}],
          "citation_normalized_percentile": {"value": 0.9}
        }"""
        val candidate = makeCandidate(
          title = "Y-chromosome haplogroup phylogeny reveals ancient DNA migration patterns",
          abstractText = Some("We analyzed Y-DNA SNP and Y-STR data using whole genome sequencing"),
          journalName = Some("Nature Genetics"),
          rawMetadata = Some(metadata)
        )
        val score = service.score(candidate)
        score must be >= 0.7
      }

      "produce low score for irrelevant paper" in {
        val metadata = """{
          "concepts": [{"display_name": "Computer Science", "score": 0.9}],
          "cited_by_count": 2
        }"""
        val candidate = makeCandidate(
          title = "Deep learning for image classification",
          journalName = Some("Journal of AI Research"),
          rawMetadata = Some(metadata)
        )
        val score = service.score(candidate)
        score must be < 0.3
      }

      "be bounded between 0 and 1" in {
        val lowCandidate = makeCandidate(title = "Completely unrelated topic")
        val highCandidate = makeCandidate(
          title = "Y-DNA haplogroup phylogenetic ancient DNA SNP Y-STR whole genome sequencing",
          journalName = Some("Nature Genetics"),
          rawMetadata = Some("""{"concepts": [{"display_name": "Haplogroup", "score": 1.0}], "citation_normalized_percentile": {"value": 1.0}}""")
        )

        service.score(lowCandidate) must be >= 0.0
        service.score(lowCandidate) must be <= 1.0
        service.score(highCandidate) must be >= 0.0
        service.score(highCandidate) must be <= 1.0
      }
    }

    "scoreCandidates" should {

      "update relevance scores for a batch" in {
        val candidates = Seq(
          makeCandidate(title = "Y-chromosome haplogroup study", journalName = Some("Nature")),
          makeCandidate(title = "Unrelated paper on economics")
        )

        val scored = service.scoreCandidates(candidates)
        scored must have size 2
        scored.head.relevanceScore mustBe defined
        scored(1).relevanceScore mustBe defined
        scored.head.relevanceScore.get must be > scored(1).relevanceScore.get
      }
    }

    "scoreBreakdown" should {

      "return all component scores" in {
        val candidate = makeCandidate(
          title = "Y-DNA haplogroup analysis",
          journalName = Some("Nature Genetics"),
          rawMetadata = Some("""{"cited_by_count": 50}""")
        )

        val breakdown = service.scoreBreakdown(candidate)
        breakdown.keywordScore must be > 0.0
        breakdown.journalScore mustBe 1.0
        breakdown.citationScore must be > 0.0
        breakdown.compositeScore mustBe service.score(candidate)
      }
    }
  }
}
