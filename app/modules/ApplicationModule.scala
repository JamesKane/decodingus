package modules

import actors.{EnaStudyUpdateActor, PublicationUpdateActor}
import com.google.inject.AbstractModule
import play.api.libs.concurrent.PekkoGuiceSupport

class ApplicationModule extends AbstractModule with PekkoGuiceSupport {
  override def configure(): Unit = {
    bindActor[PublicationUpdateActor]("publication-update-actor")
    bindActor[EnaStudyUpdateActor]("ena-study-update-actor")

    bind(classOf[Scheduler]).asEagerSingleton()
  }
}