package models

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDate

/**
 * Represents a publication with metadata details such as its title, authors, journal, and identifiers.
 *
 * @param id              An optional unique identifier for the publication, typically used internally.
 * @param pubmedId        An optional PubMed identifier, linking the publication to its record in PubMed.
 * @param doi             An optional Digital Object Identifier (DOI), providing a permanent reference to the publication.
 * @param title           The title of the publication. This is required.
 * @param authors         An optional string containing the names of the authors of the publication.
 * @param abstractSummary An optional summary or abstract of the publication.
 * @param journal         An optional journal name where the publication appeared.
 * @param publicationDate An optional date indicating when the publication was officially released.
 * @param url             An optional URL pointing to the publication's location or additional resources.
 */
case class Publication(
                        id: Option[Int] = None,
                        pubmedId: Option[String],
                        doi: Option[String],
                        title: String,
                        authors: Option[String],
                        abstractSummary: Option[String],
                        journal: Option[String],
                        publicationDate: Option[LocalDate],
                        url: Option[String]
                      )

/**
 * Companion object for the `Publication` case class.
 *
 * Provides implicit JSON formatting support for serializing and deserializing instances of `Publication`.
 */
object Publication {
  implicit val publicationFormat: OFormat[Publication] = Json.format[Publication]
}
