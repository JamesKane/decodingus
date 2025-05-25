package models.domain

/**
 * Represents a location or URL for accessing HTTP resources related to a specific sequence file.
 *
 * @param id             An optional unique identifier for the HTTP location, primarily for internal usage.
 * @param sequenceFileId The identifier of the associated sequence file. This establishes the relationship 
 *                       between the HTTP resource and the sequence file it corresponds to.
 * @param fileUrl        The URL of the file resource. This is a required field and points to the 
 *                       primary location of the sequence data.
 * @param fileIndexUrl   An optional URL for the file index resource, if applicable. This typically 
 *                       points to an index file associated with the primary file for use in operations 
 *                       like searching or alignment.
 */
case class SequenceHttpLocation(
                                 id: Option[Int],
                                 sequenceFileId: Int,
                                 fileUrl: String,
                                 fileIndexUrl: Option[String],
                               )
