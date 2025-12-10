package modules

import actors.{GenomicStudyUpdateActor, PublicationUpdateActor, YBrowseVariantUpdateActor}
import com.google.inject.AbstractModule
import play.api.libs.concurrent.PekkoGuiceSupport

class ApplicationModule extends AbstractModule with PekkoGuiceSupport {
  override def configure(): Unit = {
    bindActor[PublicationUpdateActor]("publication-update-actor")
    bindActor[GenomicStudyUpdateActor]("genomic-study-update-actor")
    bindActor[actors.PublicationDiscoveryActor]("publication-discovery-actor")
    bindActor[YBrowseVariantUpdateActor]("ybrowse-variant-update-actor")

    bind(classOf[Scheduler]).asEagerSingleton()
  }
}