package models.dal

import models.SequenceAtpLocation
import slick.jdbc.PostgresProfile.api.*

/**
 * Represents the database table `sequence_atp_location`, mapping to the `SequenceAtpLocation` case class.
 *
 * This table stores information related to the association of a sequence file with an ATP (Atmosphere Protocol) 
 * location, including details such as repository and index identifiers.
 *
 * Columns:
 * - `id`: The primary key, automatically incremented and unique for each record.
 * - `sequenceFileId`: A foreign key referencing the identifier of the sequence file associated with this location.
 * - `repoDid`: The Decentralized Identifier (DID) of the repository containing the sequence file.
 * - `recordCid`: The Content Identifier (CID) for a specific record in the repository.
 * - `recordPath`: The physical or logical path to the record within the repository.
 * - `indexDid`: An optional Decentralized Identifier (DID) for an index related to the data.
 * - `indexCid`: An optional Content Identifier (CID) for the index record.
 *
 * Primary key:
 * - `id`
 *
 * Mapping:
 * Maps the fields of the `SequenceAtpLocation` case class to the corresponding columns in the `sequence_atp_location` table.
 */
class SequenceAtpLocationTable(tag: Tag) extends Table[SequenceAtpLocation](tag, "sequence_atp_location") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def sequenceFileId = column[Int]("sequence_file_id")

  def repoDid = column[String]("repo_did")

  def recordCid = column[String]("record_cid")

  def recordPath = column[String]("record_path")

  def indexDid = column[String]("index_did")

  def indexCid = column[String]("index_cid")

  def * = (id.?, sequenceFileId, repoDid, recordCid, recordPath, indexDid, indexCid).mapTo[SequenceAtpLocation]
}
