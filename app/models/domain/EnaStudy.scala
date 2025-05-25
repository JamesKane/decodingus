package models.domain

import play.api.libs.json.{Json, OFormat}

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
case class EnaStudy(
                     id: Option[Int] = None,
                     accession: String,
                     title: String,
                     centerName: String,
                     studyName: String,
                     details: String
                   )

/**
 * Companion object for the `EnaStudy` case class.
 *
 * Provides implicit JSON formatting support for serializing and deserializing instances of `EnaStudy`.
 */
object EnaStudy {
  implicit val enaStudyFormat: OFormat[EnaStudy] = Json.format[EnaStudy]
}