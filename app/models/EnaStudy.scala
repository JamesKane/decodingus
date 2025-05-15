package models

import play.api.libs.json.{Json, OFormat}

case class EnaStudy(
                     id: Option[Int] = None,
                     accession: String,
                     title: String,
                     centerName: String,
                     studyName: String,
                     details: String
                   )

object EnaStudy {
  implicit val enaStudyFormat: OFormat[EnaStudy] = Json.format[EnaStudy]
}