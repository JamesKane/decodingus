package models.forms

import play.api.data.Form
import play.api.data.Forms.*

case class EnaBiosamplesSubmission(studyAccession: String)

object EnaBiosamplesSubmission {
  def apply(studyAccession: String): EnaBiosamplesSubmission = new EnaBiosamplesSubmission(studyAccession)
  def unapply(submission: EnaBiosamplesSubmission): Option[String] = Some(submission.studyAccession)
}

object EnaBiosamplesSubmissionForm {
  val form = Form(
    mapping(
      "studyAccession" -> nonEmptyText.transform[String](
        _.trim,
        identity
      ).verifying(
        "Invalid ENA study accession format",
        accession => accession.matches("^PRJ[EDN][A-Z]\\d+$")
      )
    )(EnaBiosamplesSubmission.apply)(EnaBiosamplesSubmission.unapply)
  )
}