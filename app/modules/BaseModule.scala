package modules

import com.google.inject.AbstractModule
import repositories.{BiosampleRepository, BiosampleRepositoryImpl, HaplogroupRepository, HaplogroupRepositoryImpl, PublicationBiosampleRepository, PublicationBiosampleRepositoryImpl, PublicationRepository, PublicationRepositoryImpl}

class BaseModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[BiosampleRepository]).to(classOf[BiosampleRepositoryImpl])
    bind(classOf[PublicationRepository]).to(classOf[PublicationRepositoryImpl])
    bind(classOf[PublicationBiosampleRepository]).to(classOf[PublicationBiosampleRepositoryImpl])
    bind(classOf[HaplogroupRepository]).to(classOf[HaplogroupRepositoryImpl])
  }
}
