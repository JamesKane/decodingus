package services

/**
 * A trait representing an email service that allows sending emails.
 */
trait EmailService {
  /**
   * Sends an email with the specified details.
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
               ): Either[String, Unit]
}