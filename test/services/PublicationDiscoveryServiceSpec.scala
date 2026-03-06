package services

import helpers.ServiceSpec
import models.domain.publications.{Publication, PublicationCandidate}
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.{PublicationCandidateRepository, PublicationRepository, PublicationSearchConfigRepository, PublicationSearchRunRepository}

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID
import scala.concurrent.Future

class PublicationDiscoveryServiceSpec extends ServiceSpec {

  val mockSearchConfigRepo: PublicationSearchConfigRepository = mock[PublicationSearchConfigRepository]
  val mockCandidateRepo: PublicationCandidateRepository = mock[PublicationCandidateRepository]
  val mockRunRepo: PublicationSearchRunRepository = mock[PublicationSearchRunRepository]
  val mockPubRepo: PublicationRepository = mock[PublicationRepository]
  val mockPubService: PublicationService = mock[PublicationService]
  val mockOpenAlexService: OpenAlexService = mock[OpenAlexService]
  val mockRelevanceScoringService: RelevanceScoringService = mock[RelevanceScoringService]

  val service = new PublicationDiscoveryService(
    mockSearchConfigRepo, mockCandidateRepo, mockRunRepo,
    mockPubRepo, mockPubService, mockOpenAlexService, mockRelevanceScoringService
  )

  override def beforeEach(): Unit = {
    reset(mockSearchConfigRepo, mockCandidateRepo, mockRunRepo,
      mockPubRepo, mockPubService, mockOpenAlexService, mockRelevanceScoringService)
  }

  val reviewerId: UUID = UUID.randomUUID()

  def makeCandidate(id: Int, status: String = "pending", doi: Option[String] = Some("10.1234/test")): PublicationCandidate =
    PublicationCandidate(
      id = Some(id), openAlexId = s"W$id", doi = doi,
      title = s"Test Publication $id", `abstract` = Some("Abstract"),
      publicationDate = Some(LocalDate.of(2025, 1, 1)),
      journalName = Some("Nature"), relevanceScore = Some(0.8),
      discoveryDate = LocalDateTime.now(), status = status,
      reviewedBy = None, reviewedAt = None, rejectionReason = None,
      rawMetadata = None
    )

  "PublicationDiscoveryService" should {

    "acceptCandidate" should {

      "accept and import via DOI" in {
        val candidate = makeCandidate(1)
        when(mockCandidateRepo.findById(1)).thenReturn(Future.successful(Some(candidate)))
        when(mockCandidateRepo.updateStatus(anyInt, anyString, any[Option[UUID]], any[Option[String]]))
          .thenReturn(Future.successful(true))
        when(mockPubService.processPublication(anyString, any[Boolean]))
          .thenReturn(Future.successful(Some(mock[Publication])))

        whenReady(service.acceptCandidate(1, reviewerId)) { result =>
          result mustBe defined
          verify(mockCandidateRepo).updateStatus(1, "accepted", Some(reviewerId), None)
          verify(mockPubService).processPublication("10.1234/test", true)
        }
      }

      "return None for nonexistent candidate" in {
        when(mockCandidateRepo.findById(999)).thenReturn(Future.successful(None))

        whenReady(service.acceptCandidate(999, reviewerId)) { result =>
          result mustBe empty
        }
      }

      "return None for candidate without DOI" in {
        val candidate = makeCandidate(1, doi = None)
        when(mockCandidateRepo.findById(1)).thenReturn(Future.successful(Some(candidate)))
        when(mockCandidateRepo.updateStatus(anyInt, anyString, any[Option[UUID]], any[Option[String]]))
          .thenReturn(Future.successful(true))

        whenReady(service.acceptCandidate(1, reviewerId)) { result =>
          result mustBe empty
          verify(mockPubService, never()).processPublication(anyString, any[Boolean])
        }
      }
    }

    "rejectCandidate" should {

      "reject with reason" in {
        when(mockCandidateRepo.updateStatus(1, "rejected", Some(reviewerId), Some("Off-topic")))
          .thenReturn(Future.successful(true))

        whenReady(service.rejectCandidate(1, reviewerId, Some("Off-topic"))) { result =>
          result mustBe true
        }
      }
    }

    "deferCandidate" should {

      "defer a candidate" in {
        when(mockCandidateRepo.updateStatus(1, "deferred", Some(reviewerId), None))
          .thenReturn(Future.successful(true))

        whenReady(service.deferCandidate(1, reviewerId)) { result =>
          result mustBe true
        }
      }
    }

    "bulkAcceptCandidates" should {

      "accept multiple candidates" in {
        for (id <- 1 to 3) {
          val candidate = makeCandidate(id)
          when(mockCandidateRepo.findById(id)).thenReturn(Future.successful(Some(candidate)))
          when(mockCandidateRepo.updateStatus(id, "accepted", Some(reviewerId), None))
            .thenReturn(Future.successful(true))
        }
        when(mockPubService.processPublication(anyString, any[Boolean]))
          .thenReturn(Future.successful(Some(mock[Publication])))

        whenReady(service.bulkAcceptCandidates(Seq(1, 2, 3), reviewerId)) { results =>
          results must have size 3
          results.count(_.isDefined) mustBe 3
        }
      }
    }

    "bulkRejectCandidates" should {

      "reject multiple candidates with reason" in {
        when(mockCandidateRepo.bulkUpdateStatus(Seq(1, 2), "rejected", reviewerId, Some("Not relevant")))
          .thenReturn(Future.successful(2))

        whenReady(service.bulkRejectCandidates(Seq(1, 2), reviewerId, Some("Not relevant"))) { count =>
          count mustBe 2
        }
      }
    }

    "bulkDeferCandidates" should {

      "defer multiple candidates" in {
        when(mockCandidateRepo.bulkUpdateStatus(Seq(1, 2, 3), "deferred", reviewerId, None))
          .thenReturn(Future.successful(3))

        whenReady(service.bulkDeferCandidates(Seq(1, 2, 3), reviewerId)) { count =>
          count mustBe 3
        }
      }
    }
  }
}
