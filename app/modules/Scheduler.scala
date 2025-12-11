package modules

import actors.PublicationUpdateActor.UpdateAllPublications
import actors.{VariantExportActor, YBrowseVariantUpdateActor}
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
                           @Named("publication-update-actor") publicationUpdateActor: ActorRef,
                           @Named("publication-discovery-actor") publicationDiscoveryActor: ActorRef,
                           @Named("ybrowse-variant-update-actor") ybrowseVariantUpdateActor: ActorRef,
                           @Named("variant-export-actor") variantExportActor: ActorRef
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

  // Schedule the PublicationDiscovery job
  try {
    quartz.schedule("PublicationDiscovery", publicationDiscoveryActor, actors.PublicationDiscoveryActor.RunDiscovery)
    logger.info("Successfully scheduled 'PublicationDiscovery' job.")
  } catch {
    case e: Exception =>
      logger.error(s"Failed to schedule 'PublicationDiscovery' job: ${e.getMessage}", e)
  }

  // Schedule the YBrowseVariantUpdate job
  try {
    quartz.schedule("YBrowseVariantUpdate", ybrowseVariantUpdateActor, YBrowseVariantUpdateActor.RunUpdate)
    logger.info("Successfully scheduled 'YBrowseVariantUpdate' job.")
  } catch {
    case e: Exception =>
      logger.error(s"Failed to schedule 'YBrowseVariantUpdate' job: ${e.getMessage}", e)
  }

  // Schedule the VariantExport job
  try {
    quartz.schedule("VariantExport", variantExportActor, VariantExportActor.RunExport)
    logger.info("Successfully scheduled 'VariantExport' job.")
  } catch {
    case e: Exception =>
      logger.error(s"Failed to schedule 'VariantExport' job: ${e.getMessage}", e)
  }
}