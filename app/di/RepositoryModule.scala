package di

import com.google.inject.AbstractModule
import repositories.{BiosampleRepository, BiosampleRepositoryImpl}

class RepositoryModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[BiosampleRepository]).to(classOf[BiosampleRepositoryImpl])
    // Bind other repositories here
  }
}
