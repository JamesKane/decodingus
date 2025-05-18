package modules

import com.google.inject.{AbstractModule, Provides}
import com.nappin.play.recaptcha.{RecaptchaSettings, RecaptchaVerifier, ResponseParser}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment}
import utils.MockRecaptchaVerifier

import javax.inject.Singleton
import scala.concurrent.ExecutionContext

class RecaptchaModule(environment: Environment, configuration: Configuration) extends AbstractModule {
  override def configure(): Unit = {}

  @Provides
  @Singleton
  def provideRecaptchaVerifier(
                                settings: RecaptchaSettings,
                                parser: ResponseParser,
                                wsClient: WSClient,
                                configuration: Configuration
                              )(implicit ec: ExecutionContext): RecaptchaVerifier = {
    val enableRecaptcha = configuration.getOptional[Boolean]("recaptcha.enable").getOrElse(false)
    if (enableRecaptcha) {
      new RecaptchaVerifier(settings, parser, wsClient)
    } else {
      new MockRecaptchaVerifier(settings, parser, wsClient)
    }
  }
}
