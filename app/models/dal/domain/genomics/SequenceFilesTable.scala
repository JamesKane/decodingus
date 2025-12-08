package models.dal.domain.genomics

import models.domain.genomics.{SequenceFile, SequenceFileAtpLocationJsonb, SequenceFileChecksumJsonb, SequenceFileHttpLocationJsonb}
import models.dal.MyPostgresProfile // Import the object itself
import models.dal.MyPostgresProfile.api.* // Import the api contents

import java.time.LocalDateTime

/**
 * Represents the table mapping for storing sequence file records in the database.
 *
 * The `SequenceFilesTable` class extends the Slick `Table` class and specifies the schema for the
 * `sequence_file` table. Each column defined here correlates to a field in the `SequenceFile` case class,
 * which models a sequence file along with its associated metadata.
 *
 * Columns:
 * - `id`: The primary key, automatically incremented for each new row.
 * - `libraryId`: A foreign key linking this sequence file to a specific library.
 * - `fileName`: The name of the file.
 * - `fileSizeBytes`: The size of the file in bytes.
 * - `fileFormat`: The format of the file (e.g., FASTQ, BAM, etc.).
 * - `checksums`: JSONB column storing a list of file checksums.
 * - `httpLocations`: JSONB column storing a list of HTTP locations.
 * - `atpLocation`: Optional JSONB column storing an AT Protocol location.
 * - `aligner`: The name of the aligner tool used in the sequence file processing, if applicable.
 * - `targetReference`: The reference genome or target used for alignment.
 * - `createdAt`: A timestamp of when this sequence file entry was created.
 * - `updatedAt`: An optional timestamp of when the file entry was last updated.
 *
 * The `*` projection defines the mapping between the table columns and the `SequenceFile` case class.
 */
class SequenceFilesTable(tag: Tag) extends MyPostgresProfile.api.Table[SequenceFile](tag, "sequence_file") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def libraryId = column[Int]("library_id")

  def fileName = column[String]("file_name")

  def fileSizeBytes = column[Long]("file_size_bytes")

  def fileFormat = column[String]("file_format")

  // New JSONB columns
  def checksums = column[List[SequenceFileChecksumJsonb]]("checksums")
  def httpLocations = column[List[SequenceFileHttpLocationJsonb]]("http_locations")
  def atpLocation = column[Option[SequenceFileAtpLocationJsonb]]("atp_location")

  def aligner = column[String]("aligner")

  def targetReference = column[String]("target_reference")

  def createdAt = column[LocalDateTime]("created_at")

  def updatedAt = column[Option[LocalDateTime]]("updated_at")

  def * = (id.?, libraryId, fileName, fileSizeBytes, fileFormat, checksums, httpLocations, atpLocation, aligner, targetReference, createdAt, updatedAt).mapTo[SequenceFile]
}
