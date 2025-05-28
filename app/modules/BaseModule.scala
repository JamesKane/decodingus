package modules

import com.google.inject.AbstractModule
import repositories.*

/**
 * A Guice module for configuring bindings between repository interfaces and their concrete implementations.
 *
 * This class extends `AbstractModule` and defines the dependency injection setup for various repository
 * interfaces used in the application. All bindings are configured using the `bind(...).to(...)` syntax, where
 * each interface is mapped to its corresponding implementation.
 *
 * This module ensures that instances of the respective interfaces are automatically injected with their
 * implementations wherever needed in the application, promoting loose coupling and easier testing.
 */
class BaseModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[BiosampleRepository]).to(classOf[BiosampleRepositoryImpl])
    bind(classOf[PublicationRepository]).to(classOf[PublicationRepositoryImpl])
    bind(classOf[PublicationBiosampleRepository]).to(classOf[PublicationBiosampleRepositoryImpl])
    bind(classOf[GenbankContigRepository]).to(classOf[GenbankContigRepositoryImpl])
    bind(classOf[VariantRepository]).to(classOf[VariantRepositoryImpl])
    bind(classOf[HaplogroupCoreRepository]).to(classOf[HaplogroupCoreRepositoryImpl])
    bind(classOf[HaplogroupRelationshipRepository]).to(classOf[HaplogroupRelationshipRepositoryImpl])
    bind(classOf[HaplogroupRevisionMetadataRepository]).to(classOf[HaplogroupRevisionMetadataRepositoryImpl])
    bind(classOf[HaplogroupRevisionRepository]).to(classOf[HaplogroupRevisionRepositoryImpl])
    bind(classOf[HaplogroupVariantMetadataRepository]).to(classOf[HaplogroupVariantMetadataRepositoryImpl])
    bind(classOf[HaplogroupVariantRepository]).to(classOf[HaplogroupVariantRepositoryImpl])
    bind(classOf[GenomicStudyRepository])
      .to(classOf[GenomicStudyRepositoryImpl])
      .asEagerSingleton()
    bind(classOf[PublicationGenomicStudyRepository])
      .to(classOf[PublicationGenomicStudyRepositoryImpl])
      .asEagerSingleton()
  }
}
