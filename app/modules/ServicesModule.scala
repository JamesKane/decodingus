package modules

import com.google.inject.AbstractModule
import play.api.Mode.Prod
import play.api.{Configuration, Environment, Mode}
import services.{AwsSesEmailService, EmailService, LoggingEmailService}

class ServicesModule(environment: Environment, configuration: Configuration) extends AbstractModule {
  override def configure(): Unit = {
    val emailService = environment.mode match {
      case Prod => classOf[AwsSesEmailService]
      case _ => classOf[LoggingEmailService]
    }

    bind(classOf[EmailService]).to(emailService)
  }
}