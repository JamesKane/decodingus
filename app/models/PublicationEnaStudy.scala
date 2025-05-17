package models

/**
 * Represents the relationship between a publication and an ENA (European Nucleotide Archive) study.
 *
 * This case class is used to associate metadata from a publication with a specific ENA study. 
 * It provides two identifiers:
 * - `publicationId`: The unique identifier of the publication.
 * - `studyId`: The unique identifier of the ENA study.
 *
 * These identifiers can be used to establish a link between the academic research or 
 * findings (publication) and the corresponding study stored in the ENA, facilitating
 * traceability and integration of research data.
 */
case class PublicationEnaStudy(publicationId: Int, studyId: Int)
