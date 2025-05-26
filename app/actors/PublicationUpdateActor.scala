package actors

import org.apache.pekko.actor.{Actor, ActorSystem, Props}
import org.apache.pekko.pattern.pipe
import play.api.Logging
import repositories.PublicationRepository
import services.OpenAlexService

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

// Pekko Streams imports
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.stream.{Materializer, ThrottleMode} // Import ThrottleMode

// Define messages for the actor
object PublicationUpdateActor {
  case object UpdateAllPublications

  case class UpdateResult(doi: String, success: Boolean, message: String)
}

class PublicationUpdateActor @javax.inject.Inject()(
                                                     openAlexService: OpenAlexService,
                                                     publicationRepository: PublicationRepository
                                                   )(implicit ec: ExecutionContext) extends Actor with Logging {

  import PublicationUpdateActor.*

  // Materializer is needed to run Pekko Streams.
  // It's often implicitly available in Actor contexts via context.system, but good to be explicit.
  implicit val materializer: Materializer = Materializer(context.system)

  // Rate limit for OpenAlex: 10 requests per second.
  // We'll aim for 1 element per 150 milliseconds to be safe.
  private val elementsPerUnit = 1
  private val perDuration = 150.millis
  private val maxBurst = 1 // No initial burst needed for continuous updates
  private val throttleMode = ThrottleMode.shaping // Make pauses to adhere to rate

  override def receive: Receive = {
    case UpdateAllPublications =>
      logger.info("PublicationUpdateActor: Starting scheduled update of all publications using Pekko Streams throttle.")
      val senderRef = sender() // Capture the sender (scheduler) for potential reply

      publicationRepository.getAllDois.flatMap { dois =>
        Source(dois.toList) // Create a Source from the list of DOIs
          .throttle(elementsPerUnit, perDuration, maxBurst, throttleMode) // Apply rate limiting
          .mapAsync(1) { doi => // mapAsync processes Futures, concurrency of 1 ensures sequential rate control
            openAlexService.fetchAndMapPublicationByDOI(doi).flatMap {
              case Some(updatedPublication) =>
                publicationRepository.savePublication(updatedPublication).map { _ =>
                  logger.debug(s"Updated publication for DOI: $doi")
                  UpdateResult(doi, success = true, "Updated")
                }.recover {
                  case e: Exception =>
                    logger.error(s"Failed to save updated publication for DOI '$doi': ${e.getMessage}", e)
                    UpdateResult(doi, success = false, s"DB save error: ${e.getMessage}")
                }
              case None =>
                logger.warn(s"Could not re-fetch data for DOI: $doi from OpenAlex.")
                Future.successful(UpdateResult(doi, success = false, "OpenAlex fetch failed"))
            }
          }
          .runWith(Sink.fold(Seq.empty[UpdateResult])((acc, elem) => acc :+ elem)) // Collect results
      }.map { results =>
        val successful = results.count(_.success)
        val failed = results.count(!_.success)
        logger.info(s"PublicationUpdateActor: Update cycle finished. Successfully updated: $successful, Failed: $failed.")
        senderRef ! "Update complete" // Acknowledge completion to the scheduler
      }.recover {
        case e: Exception =>
          logger.error(s"PublicationUpdateActor: Error during overall update process: ${e.getMessage}", e)
          senderRef ! s"Update failed: ${e.getMessage}"
      }
  }
}