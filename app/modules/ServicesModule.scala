package modules

import com.google.inject.AbstractModule
import play.api.Mode.Prod
import play.api.{Configuration, Environment, Mode}
import services.genomics.{SpecimenDonorService, SpecimenDonorServiceImpl}
import services.{AwsSesEmailService, EmailService, LoggingEmailService, TreeVersioningService, TreeVersioningServiceImpl}
import repositories.{TreeVersioningRepository, TreeVersioningRepositoryImpl}

class ServicesModule(environment: Environment, configuration: Configuration) extends AbstractModule {
  override def configure(): Unit = {
    val emailService = environment.mode match {
      case Prod => classOf[AwsSesEmailService]
      case _ => classOf[LoggingEmailService]
    }

    bind(classOf[EmailService]).to(emailService)

    bind(classOf[SpecimenDonorService])

      .to(classOf[SpecimenDonorServiceImpl])

      .asEagerSingleton()


    bind(classOf[repositories.CitizenSequenceRepository])

      .to(classOf[repositories.SlickCitizenSequenceRepository])

    bind(classOf[services.PublicationDiscoveryService]).asEagerSingleton()
    bind(classOf[services.UserPermissionHelper]).asEagerSingleton()
    bind(classOf[services.HaplogroupTreeMergeService]).asEagerSingleton()

    // Tree Versioning System
    bind(classOf[TreeVersioningRepository]).to(classOf[TreeVersioningRepositoryImpl])
    bind(classOf[TreeVersioningService]).to(classOf[TreeVersioningServiceImpl]).asEagerSingleton()
  }
}

    