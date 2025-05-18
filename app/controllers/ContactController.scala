package controllers

import com.nappin.play.recaptcha.RecaptchaVerifier

import javax.inject.*
import play.api.mvc.*
import play.api.i18n.I18nSupport
import models.Contact
import org.webjars.play.WebJarsUtil
import play.api.{Configuration, Environment, Logging}
import services.EmailService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ContactController @Inject()(
                                   val controllerComponents: ControllerComponents,
                                   emailService: EmailService,
                                   config: Configuration,
                                   verifier: RecaptchaVerifier,
                                   env: Environment
                                 )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil)
  extends BaseController with I18nSupport with Logging {

  private val recipientEmail = config.get[String]("contact.recipient-email")
  private val isProd = env.mode == play.api.Mode.Prod
  private val botRegex = "(?i)bot|crawl|spider|curl|wget|python|httpclient".r

  def show: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.contact(Contact.form))
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
          Future.successful(BadRequest(views.html.contact(form)))

        case form => form.get match {
          case contact if contact.phoneNumber.nonEmpty =>
            // Honeypot field is filled - likely spam
            logger.warn(
              s"""Spam attempt detected:
                 |IP: $clientIpAddress
                 |User-Agent: ${request.headers.get("User-Agent").getOrElse("Not provided")}
                 |Form Data:
                 |  Name: ${contact.name}
                 |  Email: ${contact.email}
                 |  Subject: ${contact.subject}
                 |  Message: ${contact.message}
                 |  Honeypot (phone): ${contact.phoneNumber}
                 |""".stripMargin
            )
            Future.successful(
              Redirect(routes.ContactController.show())
                .flashing("success" -> "Thank you for your message. We'll get back to you soon!")
            )

          case contact =>
            emailService.sendEmail(
              to = Seq(recipientEmail),
              from = contact.email,
              subject = s"Contact Form Submission: ${contact.subject}",
              body = s"""
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
                  InternalServerError(views.html.contact(Contact.form))
                    .flashing("error" -> "Sorry, there was a problem sending your message. Please try again later.")
                )
            }
        }
      }
    }
  }

}