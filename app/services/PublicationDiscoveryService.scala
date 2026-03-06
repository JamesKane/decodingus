package services

import jakarta.inject.{Inject, Singleton}
import models.domain.publications.{PublicationCandidate, PublicationSearchRun}
import play.api.Logging
import repositories.{PublicationCandidateRepository, PublicationRepository, PublicationSearchConfigRepository, PublicationSearchRunRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PublicationDiscoveryService @Inject()(
                                             searchConfigRepository: PublicationSearchConfigRepository,
                                             candidateRepository: PublicationCandidateRepository,
                                             runRepository: PublicationSearchRunRepository,
                                             publicationRepository: PublicationRepository,
                                             publicationService: PublicationService,
                                             openAlexService: OpenAlexService,
                                             relevanceScoringService: RelevanceScoringService,
                                             scoringFeedbackService: ScoringFeedbackService
                                           )(implicit ec: ExecutionContext) extends Logging {

  def acceptCandidate(candidateId: Int, reviewedBy: java.util.UUID): Future[Option[models.domain.publications.Publication]] = {
    candidateRepository.findById(candidateId).flatMap {
      case Some(candidate) =>
        // 1. Mark candidate as accepted
        candidateRepository.updateStatus(candidateId, "accepted", Some(reviewedBy), None).flatMap { success =>
          if (success) {
            // 2. Create Publication from Candidate
            // We assume the DOI is present if we are accepting it. If not, we can't import it easily via existing flow.
            candidate.doi match {
              case Some(doi) =>
                publicationService.processPublication(doi, forceRefresh = true)
              case None =>
                logger.warn(s"Candidate $candidateId has no DOI, cannot auto-import.")
                Future.successful(None)
            }
          } else {
            Future.successful(None)
          }
        }
      case None => Future.successful(None)
    }
  }

  def rejectCandidate(candidateId: Int, reviewedBy: java.util.UUID, reason: Option[String]): Future[Boolean] = {
    candidateRepository.updateStatus(candidateId, "rejected", Some(reviewedBy), reason)
  }

  def deferCandidate(candidateId: Int, reviewedBy: java.util.UUID): Future[Boolean] = {
    candidateRepository.updateStatus(candidateId, "deferred", Some(reviewedBy), None)
  }

  def bulkAcceptCandidates(candidateIds: Seq[Int], reviewedBy: java.util.UUID): Future[Seq[Option[models.domain.publications.Publication]]] = {
    Future.sequence(candidateIds.map(id => acceptCandidate(id, reviewedBy)))
  }

  def bulkRejectCandidates(candidateIds: Seq[Int], reviewedBy: java.util.UUID, reason: Option[String]): Future[Int] = {
    candidateRepository.bulkUpdateStatus(candidateIds, "rejected", reviewedBy, reason)
  }

  def bulkDeferCandidates(candidateIds: Seq[Int], reviewedBy: java.util.UUID): Future[Int] = {
    candidateRepository.bulkUpdateStatus(candidateIds, "deferred", reviewedBy, None)
  }

  def refreshLearnedWeights(): Future[Option[LearnedWeights]] = {
    scoringFeedbackService.computeLearnedWeights().map {
      case Some(weights) =>
        relevanceScoringService.applyLearnedWeights(weights)
        Some(weights)
      case None =>
        relevanceScoringService.clearLearnedWeights()
        None
    }
  }

  def runDiscovery(): Future[Unit] = {
    logger.info("Starting publication discovery run...")

    // Refresh learned weights from curator feedback before scoring new candidates
    refreshLearnedWeights().flatMap { learnedWeights =>
      learnedWeights.foreach(w => logger.info(s"Using learned weights from ${w.sampleSize} reviewed candidates."))

      searchConfigRepository.getEnabledConfigs().flatMap { configs =>
        logger.info(s"Found ${configs.size} enabled search configurations.")

        val runs = configs.map { config =>
          val startTime = System.currentTimeMillis()

          openAlexService.searchWorks(config.searchQuery).flatMap { rawCandidates =>
            val existingDoisFuture = publicationRepository.getAllDois.map(_.toSet)

            existingDoisFuture.flatMap { existingDois =>
              val newCandidates = rawCandidates.filterNot { c =>
                c.doi.exists(existingDois.contains)
              }

              val scoredCandidates = relevanceScoringService.scoreCandidates(newCandidates)

              candidateRepository.saveCandidates(scoredCandidates).flatMap { savedCandidates =>
                val endTime = System.currentTimeMillis()
                val duration = (endTime - startTime).toInt

                val run = PublicationSearchRun(
                  id = None,
                  configId = config.id.get,
                  runAt = LocalDateTime.now(),
                  candidatesFound = rawCandidates.size,
                  newCandidates = savedCandidates.size,
                  queryUsed = Some(config.searchQuery),
                  durationMs = Some(duration)
                )

                for {
                  _ <- runRepository.create(run)
                  _ <- searchConfigRepository.updateLastRun(config.id.get, LocalDateTime.now())
                } yield ()
              }
            }
          }.recover {
            case e: Exception =>
              logger.error(s"Error running discovery for config '${config.name}' (ID: ${config.id}): ${e.getMessage}", e)
          }
        }

        Future.sequence(runs).map(_ => logger.info("Publication discovery run completed."))
      }
    }
  }
}