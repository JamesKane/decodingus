package models.forms

import play.api.data.Form
import play.api.data.Forms.*

case class DoiSubmission(doi: String)

object DoiSubmission {
  def apply(doi: String): DoiSubmission = new DoiSubmission(doi)

  def unapply(submission: DoiSubmission): Option[String] = Some(submission.doi)
}

object DoiSubmissionForm {
  val form = Form(
    mapping(
      "doi" -> nonEmptyText.transform[String](
        _.trim,
        identity
      ).verifying(
        "Invalid DOI format",
        doi => doi.matches("^10\\.\\d{4,9}/[-._;()/:a-zA-Z0-9]+$")
      )
    )(DoiSubmission.apply)(DoiSubmission.unapply)
  )
}


