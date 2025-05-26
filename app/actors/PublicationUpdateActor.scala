package actors

import org.apache.pekko.actor.Actor
import play.api.Logging
import repositories.PublicationRepository
import services.OpenAlexService

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

// Pekko Streams imports
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.{Materializer, ThrottleMode}

/** Companion object for PublicationUpdateActor containing message types and result cases
 */
object PublicationUpdateActor {
  /** Message to trigger update of all publications in the system */
  case object UpdateAllPublications

  /** Represents the result of a single publication update operation
   *
   * @param doi The DOI (Digital Object Identifier) of the publication that was processed
   * @param success Whether the update operation was successful
   * @param message A descriptive message about the result of the operation
   */
  case class UpdateResult(doi: String, success: Boolean, message: String)
}

/** Actor responsible for managing publication updates from OpenAlex API with rate limiting
 *
 * This actor handles the periodic updating of publication data from OpenAlex,
 * implementing rate limiting to respect API constraints (10 requests per second).
 * It uses Pekko Streams for efficient processing and backpressure management.
 *
 * Rate limiting is implemented using Pekko Streams throttle with:
 * - 1 request per 150ms (approximately 6.67 requests per second)
 * - No initial burst
 * - Shaping mode for consistent spacing of requests
 *
 * The actor processes publications sequentially to maintain strict rate limiting
 * and logs all operations for monitoring and debugging purposes.
 *
 * @param openAlexService Service for fetching publication data from OpenAlex
 * @param publicationRepository Repository for storing and retrieving publication data
 * @param ec Implicit execution context for Future operations
 */

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

      (for {
        dois <- publicationRepository.getAllDois
        results <- Source(dois.toList)
          .throttle(elementsPerUnit, perDuration, maxBurst, throttleMode)
          .mapAsync(1) { doi =>
            for {
              publicationOpt <- openAlexService.fetchAndMapPublicationByDOI(doi)
              result <- publicationOpt match {
                case Some(updatedPublication) =>
                  (for {
                    _ <- publicationRepository.savePublication(updatedPublication)
                    _ = logger.debug(s"Updated publication for DOI: $doi")
                  } yield UpdateResult(doi, success = true, "Updated"))
                    .recover {
                      case e: Exception =>
                        logger.error(s"Failed to save updated publication for DOI '$doi': ${e.getMessage}", e)
                        UpdateResult(doi, success = false, s"DB save error: ${e.getMessage}")
                    }
                case None =>
                  logger.warn(s"Could not re-fetch data for DOI: $doi from OpenAlex.")
                  Future.successful(UpdateResult(doi, success = false, "OpenAlex fetch failed"))
              }
            } yield result
          }
          .runWith(Sink.fold(Seq.empty[UpdateResult])((acc, elem) => acc :+ elem))
        _ = {
          val successful = results.count(_.success)
          val failed = results.count(!_.success)
          logger.info(s"PublicationUpdateActor: Update cycle finished. Successfully updated: $successful, Failed: $failed.")
          senderRef ! "Update complete"
        }
      } yield ()) recover {
        case e: Exception =>
          logger.error(s"PublicationUpdateActor: Error during overall update process: ${e.getMessage}", e)
          senderRef ! s"Update failed: ${e.getMessage}"
      }
  }
}