package controllers

import jakarta.inject.{Inject, Singleton}
import models.domain.support.{MessageReply, MessageStatus}
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.{ContactMessageRepository, UserRepository}
import services.{AuthService, EmailService}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class ReplyFormData(replyText: String, sendEmail: Boolean)

@Singleton
class SupportAdminController @Inject()(
    val controllerComponents: ControllerComponents,
    contactMessageRepository: ContactMessageRepository,
    userRepository: UserRepository,
    authService: AuthService,
    emailService: EmailService
)(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil)
    extends BaseController with I18nSupport with Logging {

  private val replyForm = Form(
    mapping(
      "replyText" -> nonEmptyText(1, 4096),
      "sendEmail" -> boolean
    )(ReplyFormData.apply)(r => Some((r.replyText, r.sendEmail)))
  )

  /**
   * Check if current user has Admin role.
   */
  private def withAdminAuth[A](request: play.api.mvc.Request[A])(
      block: UUID => Future[play.api.mvc.Result]
  ): Future[play.api.mvc.Result] = {
    implicit val req: play.api.mvc.RequestHeader = request
    request.session.get("userId").map(UUID.fromString) match {
      case Some(userId) =>
        authService.hasRole(userId, "Admin").flatMap {
          case true => block(userId)
          case false =>
            Future.successful(
              Forbidden(views.html.errors.forbidden("You do not have permission to access this page."))
            )
        }
      case None =>
        Future.successful(
          Redirect(routes.AuthController.login).flashing("error" -> "Please log in to access this page.")
        )
    }
  }

  /**
   * List all contact messages for admin review.
   */
  def listMessages(status: Option[String], page: Int, pageSize: Int): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { _ =>
      val statusFilter = status.flatMap(MessageStatus.fromString)
      val offset = (page - 1) * pageSize

      for {
        messages <- contactMessageRepository.findAll(statusFilter, pageSize, offset)
        totalCount <- contactMessageRepository.countByStatus(statusFilter)
      } yield {
        val totalPages = (totalCount + pageSize - 1) / pageSize
        Ok(views.html.support.admin.messageList(messages, statusFilter, page, totalPages, pageSize))
      }
    }
  }

  /**
   * View a single message with its replies.
   */
  def viewMessage(id: UUID): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { adminUserId =>
      contactMessageRepository.findWithReplies(id).flatMap {
        case Some((message, replies)) =>
          // Mark as read if new
          val updateFuture = if (message.status == MessageStatus.New) {
            contactMessageRepository.updateStatus(id, MessageStatus.Read)
          } else {
            Future.successful(0)
          }

          // Get sender info if authenticated user
          val senderFuture = message.userId match {
            case Some(userId) => userRepository.findById(userId)
            case None => Future.successful(None)
          }

          for {
            _ <- updateFuture
            senderOpt <- senderFuture
          } yield {
            Ok(views.html.support.admin.messageDetail(message, replies, senderOpt, replyForm))
          }

        case None =>
          Future.successful(NotFound(views.html.errors.notFound("Message not found.")))
      }
    }
  }

  /**
   * Submit a reply to a message.
   */
  def submitReply(messageId: UUID): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { adminUserId =>
      contactMessageRepository.findById(messageId).flatMap {
        case Some(message) =>
          replyForm.bindFromRequest().fold(
            formWithErrors => {
              for {
                replies <- contactMessageRepository.findRepliesByMessageId(messageId)
                senderOpt <- message.userId.map(userRepository.findById).getOrElse(Future.successful(None))
              } yield {
                BadRequest(views.html.support.admin.messageDetail(message, replies, senderOpt, formWithErrors))
              }
            },
            data => {
              val now = LocalDateTime.now()
              val reply = MessageReply(
                id = None,
                messageId = messageId,
                adminUserId = adminUserId,
                replyText = data.replyText,
                emailSent = false,
                emailSentAt = None,
                createdAt = now
              )

              for {
                createdReply <- contactMessageRepository.createReply(reply)
                _ <- contactMessageRepository.updateStatus(messageId, MessageStatus.Replied)
                _ <- if (data.sendEmail && message.senderEmail.isDefined) {
                  sendReplyEmail(message, data.replyText, createdReply.id.get)
                } else {
                  Future.successful(())
                }
              } yield {
                Redirect(routes.SupportAdminController.viewMessage(messageId))
                  .flashing("success" -> "Reply sent successfully.")
              }
            }
          )

        case None =>
          Future.successful(NotFound(views.html.errors.notFound("Message not found.")))
      }
    }
  }

  /**
   * Update message status.
   */
  def updateStatus(messageId: UUID, status: String): Action[AnyContent] = Action.async { implicit request =>
    withAdminAuth(request) { _ =>
      MessageStatus.fromString(status) match {
        case Some(newStatus) =>
          contactMessageRepository.updateStatus(messageId, newStatus).map { _ =>
            Redirect(routes.SupportAdminController.viewMessage(messageId))
              .flashing("success" -> s"Status updated to ${newStatus.value}.")
          }
        case None =>
          Future.successful(BadRequest("Invalid status"))
      }
    }
  }

  /**
   * HTMX endpoint: Get unread message count badge for admins.
   */
  def adminMessageBadge: Action[AnyContent] = Action.async { implicit request =>
    // Check if user is admin before returning badge
    request.session.get("userId").map(UUID.fromString) match {
      case Some(userId) =>
        authService.hasRole(userId, "Admin").flatMap {
          case true =>
            contactMessageRepository.countUnreadForAdmin.map { count =>
              Ok(views.html.partials.messageBadge(count))
            }
          case false =>
            Future.successful(Ok(views.html.partials.messageBadge(0)))
        }
      case None =>
        Future.successful(Ok(views.html.partials.messageBadge(0)))
    }
  }

  /**
   * Send email reply to anonymous user.
   */
  private def sendReplyEmail(message: models.domain.support.ContactMessage, replyText: String, replyId: UUID): Future[Unit] = {
    message.senderEmail match {
      case Some(email) =>
        val result = emailService.sendEmail(
          to = Seq(email),
          from = "support@decoding-us.com",
          subject = s"Re: ${message.subject}",
          body =
            s"""
               |Hello ${message.senderName.getOrElse("there")},
               |
               |Thank you for contacting Decoding Us. Here is our response:
               |
               |$replyText
               |
               |---
               |Original message:
               |${message.message}
               |
               |Best regards,
               |The Decoding Us Team
               |""".stripMargin
        )

        result match {
          case Right(_) =>
            contactMessageRepository.markEmailSent(replyId).map(_ => ())
          case Left(error) =>
            logger.error(s"Failed to send reply email: $error")
            Future.successful(())
        }

      case None =>
        Future.successful(())
    }
  }
}
