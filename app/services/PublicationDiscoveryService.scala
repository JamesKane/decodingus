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
                                             openAlexService: OpenAlexService
                                           )(implicit ec: ExecutionContext) extends Logging {

  def runDiscovery(): Future[Unit] = {
    logger.info("Starting publication discovery run...")
    
    searchConfigRepository.getEnabledConfigs().flatMap { configs =>
      logger.info(s"Found ${configs.size} enabled search configurations.")
      
      val runs = configs.map { config =>
        val startTime = System.currentTimeMillis()
        
        // 1. Execute Search
        openAlexService.searchWorks(config.searchQuery).flatMap { rawCandidates =>
          
          // 2. Deduplication
          // Check against existing Publications
          val existingDoisFuture = publicationRepository.getAllDois.map(_.toSet)
          // We should also check against existing candidates to avoid duplicates in the queue
          // For simplicity, let's assume candidateRepository.saveCandidates handles some level of checking
          // or we check explicitly here. The repository implementation I wrote filters by OpenAlexId.
          
          existingDoisFuture.flatMap { existingDois =>
            val newCandidates = rawCandidates.filterNot { c =>
              c.doi.exists(existingDois.contains)
            }
            
            // 3. Calculate Relevance Score (Placeholder logic)
            // For now, let's just use 0.5 as a base score, or maybe look at citation counts if available in rawMetadata
            val scoredCandidates = newCandidates.map { c =>
              // extract simple score from raw metadata if possible, else default
              val percentile = (c.rawMetadata.get \ "citation_normalized_percentile" \ "value").asOpt[Double]
              c.copy(relevanceScore = percentile.orElse(Some(0.5)))
            }

            // 4. Save Candidates
            candidateRepository.saveCandidates(scoredCandidates).flatMap { savedCandidates =>
              val endTime = System.currentTimeMillis()
              val duration = (endTime - startTime).toInt
              
              // 5. Log Run
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
