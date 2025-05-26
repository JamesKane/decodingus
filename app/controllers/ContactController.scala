package controllers

import com.nappin.play.recaptcha.{RecaptchaVerifier, WidgetHelper}
import models.forms.Contact
import org.webjars.play.WebJarsUtil
import play.api.i18n.I18nSupport
import play.api.mvc.*
import play.api.{Configuration, Environment, Logging}
import services.EmailService

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ContactController @Inject()(
                                   val controllerComponents: ControllerComponents,
                                   emailService: EmailService,
                                   config: Configuration,
                                   verifier: RecaptchaVerifier,
                                   env: Environment,
                                   contactView: views.html.contact
                                 )(implicit ec: ExecutionContext, widgetHelper: WidgetHelper, webJarsUtil: WebJarsUtil) extends BaseController with I18nSupport with Logging {

  private val recipientEmail = config.get[String]("contact.recipient.email")
  private val isProd = env.mode == play.api.Mode.Prod
  private val botRegex = "(?i)bot|crawl|spider|curl|wget|python|httpclient".r

  def show: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(contactView(Contact.form, isProd))
  }

  def submit(): Action[AnyContent] = Action.async { implicit request =>
    val clientIpAddress = request.headers.get("X-Real-IP").getOrElse(request.remoteAddress)

    // Bot detection
    val userAgentOpt = request.headers.get("User-Agent")
    if (userAgentOpt.isEmpty || userAgentOpt.exists(agent => botRegex.findFirstMatchIn(agent).isDefined)) {
      logger.warn(s"Submission blocked due to suspicious user agent: $userAgentOpt from IP: $clientIpAddress")
      Future.successful(
        Redirect(routes.ContactController.show())
          .flashing("error" -> "Submission rejected.")
      )
    } else {
      val formValidation = if (isProd) {
        verifier.bindFromRequestAndVerify(Contact.form)
      } else {
        Future.successful(Contact.form.bindFromRequest())
      }

      formValidation.flatMap {
        case form if form.hasErrors =>
          Future.successful(BadRequest(contactView(form, isProd)))

        case form => form.get match {
          case contact if contact.phoneNumber.nonEmpty =>
            // Honeypot field is filled - likely spam
            logger.warn(s"Spam attempt detected from IP: $clientIpAddress")
            Future.successful(
              Redirect(routes.ContactController.show())
                .flashing("success" -> "Thank you for your message. We'll get back to you soon!")
            )

          case contact =>
            emailService.sendEmail(
              to = Seq(recipientEmail),
              from = contact.email,
              subject = s"Contact Form Submission: ${contact.subject}",
              body =
                s"""
                   |Name: ${contact.name}
                   |Email: ${contact.email}
                   |Subject: ${contact.subject}
                   |
                   |Message:
                   |${contact.message}
                   |""".stripMargin
            ) match {
              case Right(_) =>
                logger.info(s"Successfully sent email from ${contact.email}")
                Future.successful(
                  Redirect(routes.ContactController.show())
                    .flashing("success" -> "Thank you for your message. We'll get back to you soon!")
                )
              case Left(error) =>
                logger.error(s"Failed to send email from ${contact.email}: $error")
                Future.successful(
                  InternalServerError(contactView(Contact.form, isProd))
                    .flashing("error" -> "Sorry, there was a problem sending your message. Please try again later.")
                )
            }
        }
      }
    }
  }
}
