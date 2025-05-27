
package models.forms

import play.api.data.Form
import play.api.data.Forms._

case class PaperSubmission(
                            doi: String,
                            enaAccession: Option[String],
                            forceRefresh: Boolean)

object PaperSubmission {
  def unapply(submission: PaperSubmission): Option[(String, Option[String], Boolean)] = {
    Some((submission.doi, submission.enaAccession, submission.forceRefresh))
  }
}

object PaperSubmissionForm {
  private def extractDoi(input: String): String = {
    input.trim match {
      case url if url.startsWith("https://doi.org/") => url.substring("https://doi.org/".length)
      case url if url.startsWith("http://doi.org/") => url.substring("http://doi.org/".length)
      case doi => doi
    }
  }

  val form = Form(
    mapping(
      "doi" -> nonEmptyText.transform[String](
        extractDoi,
        identity
      ).verifying(
        "Invalid DOI format",
        doi => doi.matches("^10\\.\\d{4,9}/[-._;()/:a-zA-Z0-9]+$")
      ),
      "enaAccession" -> optional(
        text.transform[String](
          _.trim,
          identity
        ).verifying(
          "Invalid ENA accession format",
          accession => accession.isEmpty || accession.matches("^PRJ[EDN][A-Z]\\d+$")
        )
      ),
      "forceRefresh" -> boolean
    )(PaperSubmission.apply)(PaperSubmission.unapply)
  )
}

