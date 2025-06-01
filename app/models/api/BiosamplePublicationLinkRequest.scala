package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Represents a request to link a biosample to a publication using their respective identifiers.
 *
 * @param sampleAccession The accession ID of the biosample to be linked.
 * @param publicationDoi  The DOI (Digital Object Identifier) of the publication to be linked.
 */
case class BiosamplePublicationLinkRequest(
                                            sampleAccession: String,
                                            publicationDoi: String
                                          )

object BiosamplePublicationLinkRequest {
  implicit val format: OFormat[BiosamplePublicationLinkRequest] = Json.format
}