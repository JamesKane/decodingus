package modules

import actors.{GenomicStudyUpdateActor, PublicationUpdateActor}
import com.google.inject.AbstractModule
import play.api.libs.concurrent.PekkoGuiceSupport

class ApplicationModule extends AbstractModule with PekkoGuiceSupport {
  override def configure(): Unit = {
    bindActor[PublicationUpdateActor]("publication-update-actor")
    bindActor[GenomicStudyUpdateActor]("genomic-study-update-actor")

    bind(classOf[Scheduler]).asEagerSingleton()
  }
}