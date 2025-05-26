package actors

import org.apache.pekko.actor.Actor
import play.api.Logging
import repositories.EnaStudyRepository
import services.EnaIntegrationService

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{Materializer, ThrottleMode}

object EnaStudyUpdateActor {
  case object UpdateAllStudies
  case class UpdateSingleStudy(accession: String)
  case class UpdateResult(accession: String, success: Boolean, message: String)
}

class EnaStudyUpdateActor @javax.inject.Inject()(
                                                  enaService: EnaIntegrationService,
                                                  enaStudyRepository: EnaStudyRepository
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
        accessions <- enaStudyRepository.getAllAccessions
        results <- Source(accessions.toList)
          .throttle(elementsPerUnit, perDuration, maxBurst, throttleMode)
          .mapAsync(1) { accession =>
            processStudyUpdate(accession)
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

    case UpdateSingleStudy(accession) =>
      logger.info(s"Updating single ENA study: $accession")
      val senderRef = sender()

      Source.single(accession)
        .throttle(elementsPerUnit, perDuration, maxBurst, throttleMode)
        .mapAsync(1)(processStudyUpdate)
        .runWith(Sink.head)
        .map(result => senderRef ! result)
  }

  private def processStudyUpdate(accession: String): Future[UpdateResult] = {
    enaService.getEnaStudyDetails(accession).flatMap {
      case Some(study) =>
        enaStudyRepository.saveStudy(study)
          .map(_ => UpdateResult(accession, success = true, "Study updated successfully"))
          .recover {
            case e: Exception =>
              logger.error(s"Failed to save study $accession: ${e.getMessage}", e)
              UpdateResult(accession, success = false, s"Failed to save: ${e.getMessage}")
          }
      case None =>
        logger.warn(s"No study data found for accession: $accession")
        Future.successful(UpdateResult(accession, success = false, "No data found in ENA"))
    }
  }
}