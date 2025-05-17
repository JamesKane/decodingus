package modules

import com.google.inject.AbstractModule
import repositories.*

class BaseModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[BiosampleRepository]).to(classOf[BiosampleRepositoryImpl])
    bind(classOf[PublicationRepository]).to(classOf[PublicationRepositoryImpl])
    bind(classOf[PublicationBiosampleRepository]).to(classOf[PublicationBiosampleRepositoryImpl])
    bind(classOf[HaplogroupRepository]).to(classOf[HaplogroupRepositoryImpl])
    bind(classOf[GenbankContigRepository]).to(classOf[GenbankContigRepositoryImpl])
    bind(classOf[VariantRepository]).to(classOf[VariantRepositoryImpl])

  }
}
