package controllers

import com.nappin.play.recaptcha.{RecaptchaVerifier, WidgetHelper}
import models.domain.support.{ContactMessage, MessageStatus}
import models.forms.Contact
import org.webjars.play.WebJarsUtil
import play.api.i18n.I18nSupport
import play.api.mvc.*
import play.api.{Configuration, Environment, Logging}
import repositories.ContactMessageRepository
import services.EmailService

import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ContactController @Inject()(
    val controllerComponents: ControllerComponents,
    contactMessageRepository: ContactMessageRepository,
    emailService: EmailService,
    config: Configuration,
    verifier: RecaptchaVerifier,
    env: Environment,
    contactView: views.html.contact
)(implicit ec: ExecutionContext, widgetHelper: WidgetHelper, webJarsUtil: WebJarsUtil)
    extends BaseController with I18nSupport with Logging {

  private val recipientEmail = config.get[String]("contact.recipient.email")
  private val serviceEmail = "info@decoding-us.com"
  private val isProd = env.mode == play.api.Mode.Prod
  private val botRegex = "(?i)bot|crawl|spider|curl|wget|python|httpclient".r

  def show: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    val isAuthenticated = request.session.get("userId").isDefined
    Ok(contactView(Contact.form, isProd, isAuthenticated))
  }

  def submit(): Action[AnyContent] = Action.async { implicit request =>
    val clientIpAddress = request.headers.get("X-Real-IP").getOrElse(request.remoteAddress)
    val userIdOpt = request.session.get("userId").map(UUID.fromString)
    val isAuthenticated = userIdOpt.isDefined

    // Bot detection
    val userAgentOpt = request.headers.get("User-Agent")
    if (userAgentOpt.isEmpty || userAgentOpt.exists(agent => botRegex.findFirstMatchIn(agent).isDefined)) {
      logger.warn(s"Submission blocked due to suspicious user agent: $userAgentOpt from IP: $clientIpAddress")
      Future.successful(
        Redirect(routes.ContactController.show())
          .flashing("error" -> "Submission rejected.")
      )
    } else {
      // For anonymous users, validate with reCAPTCHA in production
      val formValidation = if (isProd && !isAuthenticated) {
        verifier.bindFromRequestAndVerify(Contact.form)
      } else {
        Future.successful(Contact.form.bindFromRequest())
      }

      formValidation.flatMap {
        case form if form.hasErrors =>
          Future.successful(BadRequest(contactView(form, isProd, isAuthenticated)))

        case form => form.get match {
          case contact if contact.phoneNumber.nonEmpty =>
            // Honeypot field is filled - likely spam
            logger.warn(s"Spam attempt detected from IP: $clientIpAddress")
            Future.successful(
              Redirect(routes.ContactController.show())
                .flashing("success" -> "Thank you for your message. We'll get back to you soon!")
            )

          case contact =>
            val now = LocalDateTime.now()
            val ipHash = hashIpAddress(clientIpAddress)

            val message = ContactMessage(
              id = None,
              userId = userIdOpt,
              senderName = if (isAuthenticated) None else Some(contact.name),
              senderEmail = if (isAuthenticated) None else Some(contact.email),
              subject = contact.subject,
              message = contact.message,
              status = MessageStatus.New,
              ipAddressHash = Some(ipHash),
              userAgent = userAgentOpt,
              createdAt = now,
              updatedAt = now
            )

            contactMessageRepository.create(message).map { created =>
              logger.info(s"Contact message ${created.id.get} created from ${userIdOpt.map(_.toString).getOrElse(contact.email)}")

              // Send notification email to admins
              sendAdminNotification(created, contact)

              Redirect(routes.ContactController.show())
                .flashing("success" -> "Thank you for your message. We'll get back to you soon!")
            }.recover { case e: Exception =>
              logger.error(s"Failed to save contact message", e)
              InternalServerError(contactView(Contact.form, isProd, isAuthenticated))
                .flashing("error" -> "Sorry, there was a problem sending your message. Please try again later.")
            }
        }
      }
    }
  }

  /**
   * Show messages for authenticated user (their own message history).
   * Also marks all messages as viewed to clear the notification badge.
   */
  def myMessages: Action[AnyContent] = Action.async { implicit request =>
    request.session.get("userId").map(UUID.fromString) match {
      case Some(userId) =>
        for {
          _ <- contactMessageRepository.updateUserLastViewedAll(userId)
          messages <- contactMessageRepository.findByUserId(userId)
        } yield Ok(views.html.support.myMessages(messages))
      case None =>
        Future.successful(Redirect(routes.AuthController.login).flashing("error" -> "Please log in to view your messages."))
    }
  }

  /**
   * HTMX endpoint: Get unread reply count badge for authenticated user.
   */
  def userMessageBadge: Action[AnyContent] = Action.async { implicit request =>
    request.session.get("userId").map(UUID.fromString) match {
      case Some(userId) =>
        contactMessageRepository.countUnreadRepliesForUser(userId).map { count =>
          Ok(views.html.partials.messageBadge(count))
        }
      case None =>
        Future.successful(Ok(views.html.partials.messageBadge(0)))
    }
  }

  private def sendAdminNotification(message: ContactMessage, contact: Contact.ContactDTO): Unit = {
    val senderInfo = message.userId match {
      case Some(_) => s"Authenticated User (ID: ${message.userId.get})"
      case None => s"${contact.name} <${contact.email}>"
    }

    emailService.sendEmail(
      to = Seq(recipientEmail),
      from = serviceEmail,
      subject = s"[DecodingUs Contact] ${contact.subject}",
      body =
        s"""
           |New contact message received:
           |
           |From: $senderInfo
           |Subject: ${contact.subject}
           |
           |Message:
           |${contact.message}
           |
           |---
           |Message ID: ${message.id.getOrElse("N/A")}
           |View in admin panel: /admin/messages/${message.id.getOrElse("")}
           |""".stripMargin
    ) match {
      case Right(_) => logger.info(s"Admin notification sent for message ${message.id}")
      case Left(error) => logger.error(s"Failed to send admin notification: $error")
    }
  }

  private def hashIpAddress(ip: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(ip.getBytes("UTF-8"))
    hash.map("%02x".format(_)).mkString
  }
}
