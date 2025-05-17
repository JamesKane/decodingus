package models

/**
 * Represents the association of a sequence file with an ATP (Atmosphere Protocol) location.
 *
 * @param id             An optional unique identifier for the record, typically used for internal purposes.
 * @param sequenceFileId The unique identifier representing the sequence file associated with this location.
 * @param repoDID        The Decentralized Identifier (DID) of the repository where the sequence file is stored.
 * @param recordCID      The unique Content Identifier (CID) for the specific record in the repository.
 * @param recordPath     The file path or storage location of the record within the repository.
 * @param indexDID       An optional Decentralized Identifier (DID) representing an index for the data.
 * @param indexCID       An optional Content Identifier (CID) for the index associated with the record.
 */
case class SequenceAtpLocation(
                                id: Option[Int],
                                sequenceFileId: Int,
                                repoDID: String,
                                recordCID: String,
                                recordPath: String,
                                indexDID: Option[String],
                                indexCID: Option[String],
                              )
