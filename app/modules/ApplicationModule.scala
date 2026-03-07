package modules

import actors.{GenomicStudyUpdateActor, MatchDiscoveryActor, PublicationUpdateActor, VariantExportActor, YBrowseVariantUpdateActor}
import com.google.inject.AbstractModule
import play.api.libs.concurrent.PekkoGuiceSupport
import services.ibd.{MatchDiscoveryService, MatchDiscoveryServiceImpl, PopulationAnalysisService, PopulationAnalysisServiceImpl}

class ApplicationModule extends AbstractModule with PekkoGuiceSupport {
  override def configure(): Unit = {
    bindActor[PublicationUpdateActor]("publication-update-actor")
    bindActor[GenomicStudyUpdateActor]("genomic-study-update-actor")
    bindActor[actors.PublicationDiscoveryActor]("publication-discovery-actor")
    bindActor[YBrowseVariantUpdateActor]("ybrowse-variant-update-actor")
    bindActor[VariantExportActor]("variant-export-actor")
    bindActor[MatchDiscoveryActor]("match-discovery-actor")

    bind(classOf[PopulationAnalysisService]).to(classOf[PopulationAnalysisServiceImpl])
    bind(classOf[MatchDiscoveryService]).to(classOf[MatchDiscoveryServiceImpl])

    bind(classOf[Scheduler]).asEagerSingleton()
  }
}