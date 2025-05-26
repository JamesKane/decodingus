package models.domain.publications

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDate

case class Publication(
                        id: Option[Int],
                        openAlexId: Option[String],
                        pubmedId: Option[String],
                        doi: Option[String],
                        title: String,
                        authors: Option[String],
                        abstractSummary: Option[String],
                        journal: Option[String],
                        publicationDate: Option[LocalDate],
                        url: Option[String],
                        citationNormalizedPercentile: Option[Float],
                        citedByCount: Option[Int],
                        openAccessStatus: Option[String],
                        openAccessUrl: Option[String],
                        primaryTopic: Option[String],
                        publicationType: Option[String],
                        publisher: Option[String]
                      )

/**
 * Companion object for the `Publication` case class.
 *
 * Provides implicit JSON formatting support for serializing and deserializing instances of `Publication`.
 */
object Publication {
  implicit val publicationFormat: OFormat[Publication] = Json.format[Publication]
}
