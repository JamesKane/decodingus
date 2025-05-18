package utils

import com.nappin.play.recaptcha.{RecaptchaSettings, RecaptchaVerifier, ResponseParser, WidgetHelper}
import jakarta.inject.Inject
import play.api.data.Form
import play.api.data.FormBinding.Implicits.formBinding
import play.api.libs.ws.WSClient
import play.api.mvc.Request

import scala.concurrent.{ExecutionContext, Future}

class MockRecaptchaVerifier @Inject()(
                                       settings: RecaptchaSettings,
                                       parser: ResponseParser,
                                       wsClient: WSClient
                                     )(implicit ec: ExecutionContext) extends RecaptchaVerifier(settings, parser, wsClient) {

  def bindFromRequestAndVerify[A](form: Form[A], request: Request[_], helper: WidgetHelper): Future[Form[A]] = {
    implicit val binding = formBinding
    Future.successful(form.bindFromRequest()(request, binding))
  }
}


