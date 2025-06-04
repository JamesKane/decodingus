package services

import software.amazon.awssdk.services.ses.SesClient
import software.amazon.awssdk.services.ses.model.{
  SendEmailRequest,
  SendEmailResponse,
  Destination,
  Message,
  Content,
  Body
}
import software.amazon.awssdk.regions.Region

import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logging}
import scala.util.Try
import scala.jdk.CollectionConverters.*

/**
 * A concrete implementation of the EmailService trait that uses Amazon SES to send emails.
 *
 * @param configuration Play Framework configuration to get AWS settings
 */
@Singleton
class AwsSesEmailService @Inject()(configuration: Configuration) extends EmailService with Logging {

  private val sesClient: SesClient = {
    val region = configuration
      .getOptional[String]("aws.region")
      .getOrElse("us-east-1")

    SesClient.builder()
      .region(Region.of(region))
      .build()
  }

  /**
   * Sends an email using Amazon SES.
   *
   * @param to      the list of recipient email addresses
   * @param from    the sender's email address
   * @param subject the subject of the email
   * @param body    the body content of the email
   * @return Either a String containing an error message in case of failure, or a Unit upon successful email delivery
   */
  def sendEmail(
                 to: Seq[String],
                 from: String,
                 subject: String,
                 body: String
               ): Either[String, Unit] = {
    logger.info(s"Sending Contact Request: ${(to, from, subject, body)}")
    Try {
      val destination = Destination.builder()
        .toAddresses(to.asJava)
        .build()

      val messageBody = Body.builder()
        .text(Content.builder()
          .data(body)
          .charset("UTF-8")
          .build())
        .build()

      val message = Message.builder()
        .subject(Content.builder()
          .data(subject)
          .charset("UTF-8")
          .build())
        .body(messageBody)
        .build()

      val request = SendEmailRequest.builder()
        .source(from)
        .destination(destination)
        .message(message)
        .build()

      val response: SendEmailResponse = sesClient.sendEmail(request)
      logger.info(s"Email sent successfully. MessageId: ${response.messageId()}")
      Right(())
    }.recover { case e: Exception =>
      val errorMessage = s"Failed to send email: ${e.getMessage}"
      logger.error(errorMessage, e)
      Left(errorMessage)
    }.get
  }
}