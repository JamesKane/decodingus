package controllers

import actors.PublicationDiscoveryActor
import jakarta.inject.{Inject, Named, Singleton}
import org.apache.pekko.actor.ActorRef
import play.api.Logging
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

@Singleton
class PublicationDiscoveryController @Inject()(
                                                val controllerComponents: ControllerComponents,
                                                @Named("publication-discovery-actor") publicationDiscoveryActor: ActorRef
                                              ) extends BaseController with Logging {

  def triggerDiscovery(): Action[AnyContent] = Action {
    logger.info("Manually triggering publication discovery via API.")
    publicationDiscoveryActor ! PublicationDiscoveryActor.RunDiscovery
    Ok("Publication discovery run triggered.")
  }
}
