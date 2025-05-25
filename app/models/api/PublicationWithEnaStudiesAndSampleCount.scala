package models.api

import models.domain.publications.{EnaStudy, Publication}
import play.api.libs.json.{Json, OFormat}

/**
 * Represents a publication along with its associated ENA studies and the total number of samples.
 *
 * @param publication The publication containing metadata such as title, authors, journal, and identifiers.
 * @param enaStudies  A collection of ENA studies related to the publication, each described with details 
 *                    such as accession, title, and institution.
 * @param sampleCount The total number of samples associated with the ENA studies in this publication.
 */
case class PublicationWithEnaStudiesAndSampleCount(
                                                    publication: Publication,
                                                    enaStudies: Seq[EnaStudy],
                                                    sampleCount: Int,
                                                  )

/**
 * Companion object for the `PublicationWithEnaStudiesAndSampleCount` case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing
 * instances of `PublicationWithEnaStudiesAndSampleCount` using the Play Framework's JSON library.
 *
 * This formatter enables seamless conversion of `PublicationWithEnaStudiesAndSampleCount` objects
 * to and from JSON representation, ensuring compatibility with APIs and storage systems that rely on JSON.
 */
object PublicationWithEnaStudiesAndSampleCount {
  implicit val publicationWithEnaStudiesAndSampleCountFormat: OFormat[PublicationWithEnaStudiesAndSampleCount] =
    Json.format[PublicationWithEnaStudiesAndSampleCount]
}
