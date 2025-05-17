package modules

import com.google.inject.AbstractModule
import repositories.*

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
  }
}
