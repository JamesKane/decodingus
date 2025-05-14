package models

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDate

case class Publication(
                        id: Option[Int] = None,
                        pubmedId: Option[String],
                        doi: Option[String],
                        title: String,
                        journal: Option[String],
                        publicationDate: Option[LocalDate],
                        url: Option[String]
                      )

object Publication {
  implicit val publicationFormat: OFormat[Publication] = Json.format[Publication]
}
