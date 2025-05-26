package modules

import actors.PublicationUpdateActor.UpdateAllPublications
import jakarta.inject.{Inject, Named, Singleton}
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.extension.quartz.QuartzSchedulerExtension
import play.api.Logging

/**
 * Schedules the various background jobs using Pekko Quartz.
 * This class is eager-loaded to ensure jobs are scheduled on application startup.
 */
@Singleton
class Scheduler @Inject()(
                           system: ActorSystem,
                           @Named("publication-update-actor") publicationUpdateActor: ActorRef
                         ) extends Logging {

  private val quartz = QuartzSchedulerExtension(system)

  // Schedule the PublicationUpdater job
  try {
    quartz.schedule("PublicationUpdater", publicationUpdateActor, UpdateAllPublications)
    logger.info("Successfully scheduled 'PublicationUpdater' job to send UpdateAllPublications message.")
  } catch {
    case e: Exception =>
      logger.error(s"Failed to schedule 'PublicationUpdater' job: ${e.getMessage}", e)
  }
}