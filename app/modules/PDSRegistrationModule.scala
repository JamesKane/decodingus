package modules

import com.google.inject.AbstractModule
import repositories.PDSRegistrationRepository
import services.{ATProtocolClient, PDSRegistrationService}

class PDSRegistrationModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[ATProtocolClient]).asEagerSingleton()
    bind(classOf[PDSRegistrationRepository]).asEagerSingleton()
    bind(classOf[PDSRegistrationService]).asEagerSingleton()
  }
}
