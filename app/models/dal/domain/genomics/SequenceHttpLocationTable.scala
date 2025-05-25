package models.dal.domain.genomics

import models.domain.genomics.SequenceHttpLocation
import slick.jdbc.PostgresProfile.api.*

/**
 * Represents the database table definition for storing HTTP locations related to sequence files.
 *
 * This table is designed to maintain references to HTTP resources associated with sequence files.
 * Each entry in this table corresponds to one HTTP location, which includes the main file URL 
 * and an optional file index URL for additional metadata or indexing purposes.
 *
 * @constructor Initializes the `SequenceHttpLocationTable` with Slick's tag identifier, linking it to
 *              the `sequence_http_location` table in the database.
 *
 *              Columns:
 *  - `id`: The primary identifier of the sequence HTTP location. It is auto-incremented and serves as the table's primary key.
 *  - `sequenceFileId`: Identifier that establishes a foreign key relationship with a sequence file record,
 *    linking the HTTP location to its associated sequence file.
 *  - `fileUrl`: The URL for the HTTP location of the main file. This is a required field ensuring the existence
 *    of a valid file location.
 *  - `fileIndexUrl`: An optional URL for the file index, providing metadata or indexing functionality 
 *    associated with the main file.
 *
 * Mapping:
 *  - Maps columns within the table to the `SequenceHttpLocation` case class, enabling easy interaction
 *    with database records in a type-safe way.
 */
class SequenceHttpLocationTable(tag: Tag) extends Table[SequenceHttpLocation](tag, "sequence_http_location") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def sequenceFileId = column[Int]("sequence_file_id")

  def fileUrl = column[String]("file_url")

  def fileIndexUrl = column[Option[String]]("file_index_url")

  def * = (id.?, sequenceFileId, fileUrl, fileIndexUrl).mapTo[SequenceHttpLocation]
}
