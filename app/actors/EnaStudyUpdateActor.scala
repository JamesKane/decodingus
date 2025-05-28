package actors

import org.apache.pekko.actor.Actor
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{Materializer, ThrottleMode}
import play.api.Logging
import repositories.{BiosampleRepository, GenomicStudyRepository, PublicationBiosampleRepository, PublicationGenomicStudyRepository}
import models.domain.publications.{PublicationGenomicStudy, PublicationBiosample}
import services.EnaIntegrationService

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

object EnaStudyUpdateActor {
  case object UpdateAllStudies
  case class UpdateSingleStudy(accession: String, publicationId: Option[Int] = None)
  case class UpdateResult(accession: String, success: Boolean, message: String)
}

class EnaStudyUpdateActor @javax.inject.Inject()(
                                                  enaService: EnaIntegrationService,
                                                  enaStudyRepository: GenomicStudyRepository,
                                                  biosampleRepository: BiosampleRepository,
                                                  publicationEnaStudyRepository: PublicationGenomicStudyRepository,
                                                  publicationBiosampleRepository: PublicationBiosampleRepository
                                                )(implicit ec: ExecutionContext)
  extends Actor
    with Logging {

  import EnaStudyUpdateActor.*

  implicit val materializer: Materializer = Materializer(context.system)

  // Rate limiting configuration - 1 request per second to be conservative
  private val elementsPerUnit = 1
  private val perDuration = 1.second
  private val maxBurst = 1
  private val throttleMode = ThrottleMode.shaping

  override def receive: Receive = {
    case UpdateAllStudies =>
      logger.info("Starting scheduled update of all ENA studies")
      val senderRef = sender()

      (for {
        // First get all existing publication-ENA study associations
        existingLinks <- publicationEnaStudyRepository.findAll()
        accessions <- enaStudyRepository.getAllAccessions
        results <- Source(accessions.toList)
          .throttle(elementsPerUnit, perDuration, maxBurst, throttleMode)
          .mapAsync(1) { accession =>
            // First get the study ID from accession, then look for publication links
            enaStudyRepository.findIdByAccession(accession).flatMap { studyIdOpt =>
              val publicationId = studyIdOpt.flatMap(studyId =>
                existingLinks.find(_.studyId == studyId).map(_.publicationId)
              )
              processStudyUpdate(accession, publicationId)
            }
          }
          .runWith(Sink.fold(Seq.empty[UpdateResult])((acc, elem) => acc :+ elem))
        _ = {
          val successful = results.count(_.success)
          val failed = results.count(!_.success)
          logger.info(s"ENA update cycle finished. Success: $successful, Failed: $failed")
          senderRef ! "Update complete"
        }
      } yield ()) recover {
        case e: Exception =>
          logger.error(s"Error during ENA update process: ${e.getMessage}", e)
          senderRef ! s"Update failed: ${e.getMessage}"
      }


    case UpdateSingleStudy(accession, publicationId) =>
      logger.info(s"Updating single ENA study: $accession${publicationId.fold("")(id => s" for publication $id")}")
      val senderRef = sender()

      Source.single(accession)
        .throttle(elementsPerUnit, perDuration, maxBurst, throttleMode)
        .mapAsync(1)(acc => processStudyUpdate(acc, publicationId))
        .runWith(Sink.head)
        .map(result => senderRef ! result)
  }

  private def processStudyUpdate(accession: String, publicationId: Option[Int]): Future[UpdateResult] = {
    (for {
      studyOpt <- enaService.getEnaStudyDetails(accession)
      result <- studyOpt match {
        case Some(study) =>
          for {
            savedStudy <- enaStudyRepository.saveStudy(study)
            biosamples <- enaService.getBiosamplesForStudy(accession)
            savedBiosamples <- if (biosamples.nonEmpty) {
              logger.info(s"Starting to upsert ${biosamples.size} biosamples for study $accession")
              biosampleRepository.upsertMany(biosamples)
            } else Future.successful(Seq.empty)
            // Create publication relationships if publicationId is provided
            _ <- publicationId match {
              case Some(pubId) =>
                for {
                  // Link study to publication
                  _ <- publicationEnaStudyRepository.create(PublicationGenomicStudy(
                    publicationId = pubId,
                    studyId = savedStudy.id.get
                  ))
                  // Link biosamples to publication
                  _ <- if (savedBiosamples.nonEmpty) {
                    logger.info(s"Creating ${savedBiosamples.size} publication-biosample links for publication $pubId")
                    Future.sequence(
                      savedBiosamples.flatMap(_.id).map { biosampleId =>
                        publicationBiosampleRepository.create(PublicationBiosample(
                          publicationId = pubId,
                          biosampleId = biosampleId
                        ))
                      }
                    )
                  } else Future.successful(())
                } yield ()
              case None => Future.successful(())
            }
          } yield {
            val biosampleCount = savedBiosamples.size
            UpdateResult(
              accession,
              success = true,
              s"Study updated successfully with $biosampleCount biosamples${publicationId.map(id => s" and linked to publication $id").getOrElse("")}"
            )
          }
        case None =>
          logger.warn(s"No study data found for accession: $accession")
          Future.successful(UpdateResult(accession, success = false, "No data found in ENA"))
      }
    } yield result).recover {
      case e: Exception =>
        logger.error(s"Failed to process study $accession: ${e.getMessage}", e)
        UpdateResult(accession, success = false, s"Failed to process: ${e.getMessage}")
    }
  }
}