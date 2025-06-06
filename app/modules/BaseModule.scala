package modules

import com.google.inject.{AbstractModule, TypeLiteral}
import models.domain.genomics.SequenceHttpLocation
import repositories.*
import services.{AccessionNumberGenerator, BiosampleAccessionGenerator}

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
    bind(classOf[BiosampleRepository])
      .to(classOf[BiosampleRepositoryImpl])
      .asEagerSingleton()

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
    
    bind(classOf[BiosampleOriginalHaplogroupRepository])
      .to(classOf[BiosampleOriginalHaplogroupRepositoryImpl])
      .asEagerSingleton()

    bind(new TypeLiteral[SequenceLocationRepository[SequenceHttpLocation]]() {})
      .to(classOf[SequenceHttpLocationRepositoryImpl])
      .asEagerSingleton()

    bind(classOf[SequenceFileChecksumRepository])
      .to(classOf[SequenceFileChecksumRepositoryImpl])
      .asEagerSingleton()

    bind(classOf[SequenceFileRepository])
      .to(classOf[SequenceFileRepositoryImpl])
      .asEagerSingleton()

    bind(classOf[SequenceLibraryRepository])
      .to(classOf[SequenceLibraryRepositoryImpl])
      .asEagerSingleton()

    bind(classOf[AccessionNumberGenerator])
      .to(classOf[BiosampleAccessionGenerator])
      .asEagerSingleton()

    bind(classOf[SpecimenDonorRepository])
      .to(classOf[SpecimenDonorRepositoryImpl])
      .asEagerSingleton()

  }
}
