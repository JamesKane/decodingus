package models.domain.publications

import play.api.libs.json.*

enum StudySource:
  case ENA, NCBI_BIOPROJECT, NCBI_GENBANK

object StudySource {
  implicit val studySourceReads: Reads[StudySource] = Reads { json =>
    json.validate[String].map(s => StudySource.valueOf(s))
  }

  implicit val studySourceWrites: Writes[StudySource] = Writes { source =>
    JsString(source.toString)
  }

  implicit val studySourceFormat: Format[StudySource] =
    Format(studySourceReads, studySourceWrites)
}

/**
 * Represents an ENA (European Nucleotide Archive) study with details about its accession, 
 * title, institution, and other metadata.
 *
 * @param id         An optional unique identifier for the study.
 * @param accession  The accession number for the study, typically used as a unique reference in databases.
 * @param title      The title of the study, providing a brief description or summary.
 * @param centerName The name of the center or institution responsible for the study.
 * @param studyName  The name of the study, potentially providing additional context about its purpose or scope.
 * @param details    A textual description or additional information related to the study.
 */
case class GenomicStudy(
                         id: Option[Int] = None,
                         accession: String,
                         title: String,
                         centerName: String,
                         studyName: String,
                         details: String,
                         source: StudySource,
                         submissionDate: Option[java.time.LocalDate] = None,
                         lastUpdate: Option[java.time.LocalDate] = None,
                         bioProjectId: Option[String] = None,
                         molecule: Option[String] = None,
                         topology: Option[String] = None,
                         taxonomyId: Option[Int] = None,
                         version: Option[String] = None
                       )

/**
 * Companion object for the `EnaStudy` case class.
 *
 * Provides implicit JSON formatting support for serializing and deserializing instances of `EnaStudy`.
 */
object GenomicStudy {
  implicit val enaStudyFormat: OFormat[GenomicStudy] = Json.format[GenomicStudy]
}