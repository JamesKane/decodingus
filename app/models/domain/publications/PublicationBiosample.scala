package models.domain.publications

/**
 * Represents an association between a publication and a biological sample within the system.
 *
 * @param publicationId The unique identifier of the publication associated with the biosample.
 * @param biosampleId   The unique identifier of the biosample associated with the publication.
 */
case class PublicationBiosample(publicationId: Int, biosampleId: Int)
