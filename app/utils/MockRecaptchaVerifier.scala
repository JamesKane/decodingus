package utils

import com.nappin.play.recaptcha.{RecaptchaSettings, RecaptchaVerifier, ResponseParser, WidgetHelper}
import jakarta.inject.Inject
import play.api.data.Form
import play.api.data.FormBinding.Implicits.formBinding
import play.api.libs.ws.WSClient
import play.api.mvc.Request

import scala.concurrent.{ExecutionContext, Future}

/**
 * A mock implementation of the `RecaptchaVerifier` class used to bypass Recaptcha validation during development
 * or testing when Recaptcha is disabled.
 *
 * This class provides an alternative `RecaptchaVerifier` implementation, allowing form binding and verification
 * without actual Recaptcha validation. It is typically used in scenarios where Recaptcha is not enabled, reducing
 * dependency on external Recaptcha services.
 *
 * @constructor Initializes the `MockRecaptchaVerifier` with the necessary dependencies.
 * @param settings the Recaptcha settings for configuration purposes
 * @param parser   the response parser for processing Recaptcha responses (unused in this implementation)
 * @param wsClient the web service client for handling HTTP requests (unused in this implementation)
 * @param ec       the implicit execution context used for handling asynchronous operations
 */
class MockRecaptchaVerifier @Inject()(
                                       settings: RecaptchaSettings,
                                       parser: ResponseParser,
                                       wsClient: WSClient
                                     )(implicit ec: ExecutionContext) extends RecaptchaVerifier(settings, parser, wsClient) {

  /**
   * Binds a form from an HTTP request and performs optional verification using a widget helper.
   *
   * This method allows binding request data to a form while enabling hooks for additional
   * processing or validation via the provided widget helper. It is typically utilized
   * when handling form submissions in scenarios where recaptcha or similar mechanisms need
   * to be considered for verification.
   *
   * @param form    the form to bind the request data to
   * @param request the HTTP request containing the data to bind
   * @param helper  the widget helper used to assist with additional verification steps
   * @return a future containing the bound form object
   */
  def bindFromRequestAndVerify[A](form: Form[A], request: Request[_], helper: WidgetHelper): Future[Form[A]] = {
    implicit val binding = formBinding
    Future.successful(form.bindFromRequest()(request, binding))
  }
}


