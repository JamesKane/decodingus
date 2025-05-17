package modules

import com.google.inject.AbstractModule
import services.{EmailService, LoggingEmailService}

class ServicesModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[EmailService]).to(classOf[LoggingEmailService])
  }
}