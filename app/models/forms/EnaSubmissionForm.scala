package models.forms

import play.api.data.Form
import play.api.data.Forms.*

case class EnaAccessionSubmission(accession: String)

object EnaAccessionSubmission {
  def apply(accession: String): EnaAccessionSubmission = new EnaAccessionSubmission(accession)
  def unapply(submission: EnaAccessionSubmission): Option[String] = Some(submission.accession)
}

object EnaAccessionSubmissionForm {
  val form = Form(
    mapping(
      "accession" -> nonEmptyText.transform[String](
        _.trim,
        identity
      ).verifying(
        "Invalid ENA accession format",
        accession => accession.matches("^PRJ[EDN][A-Z]\\d+$")
      )
    )(EnaAccessionSubmission.apply)(EnaAccessionSubmission.unapply)
  )
}