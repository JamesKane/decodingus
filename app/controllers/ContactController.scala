package controllers

import javax.inject.*
import play.api.mvc.*
import play.api.i18n.I18nSupport
import models.Contact
import org.webjars.play.WebJarsUtil
import services.EmailService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ContactController @Inject()(
                                   val controllerComponents: ControllerComponents,
                                   emailService: EmailService
                                 )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController with I18nSupport {

  def show: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.contact(Contact.form))
  }

  def submit(): Action[AnyContent] = Action.async { implicit request =>
    Contact.form.bindFromRequest().fold(
      formWithErrors =>
        Future.successful(BadRequest(views.html.contact(formWithErrors))),
      contact => {
        if (contact.phoneNumber.nonEmpty) {
          // Honeypot field is filled - likely spam
          Future.successful(
            Redirect(routes.ContactController.show())
              .flashing("success" -> "Thank you for your message. We'll get back to you soon!")
          )
        } else {
          val recipientEmail = "your-email@domain.com" // Configure this in application.conf

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
              Future.successful(
                Redirect(routes.ContactController.show())
                  .flashing("success" -> "Thank you for your message. We'll get back to you soon!")
              )
            case Left(error) =>
              Future.successful(
                InternalServerError(views.html.contact(Contact.form))
                  .flashing("error" -> "Sorry, there was a problem sending your message. Please try again later.")
              )
          }
        }
      }
    )
  }
}