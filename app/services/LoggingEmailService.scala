package services

import play.api.{Logger, Logging}

import javax.inject.Singleton

/**
 * A concrete implementation of the EmailService trait that logs email details instead of actually sending emails.
 * This class is useful for debugging or testing purposes where sending emails is not required.
 *
 * The email details such as recipients, sender, subject, and body are logged using the provided logger from the Logging trait.
 */
@Singleton
class LoggingEmailService extends EmailService with Logging {
  
  /**
   * Sends an email with the specified details and logs the email information.
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
    logger.info(
      s"""
         |New email:
         |To: ${to.mkString(", ")}
         |From: $from
         |Subject: $subject
         |
         |$body
         |""".stripMargin
    )
    Right(())
  }
}