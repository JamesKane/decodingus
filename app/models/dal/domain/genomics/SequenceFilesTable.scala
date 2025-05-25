package models.dal.domain.genomics

import models.domain.genomics.SequenceFile
import slick.jdbc.PostgresProfile.api.*

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
 * - `fileMd5`: The MD5 checksum of the file for verifying data integrity.
 * - `fileFormat`: The format of the file (e.g., FASTQ, BAM, etc.).
 * - `aligner`: The name of the aligner tool used in the sequence file processing, if applicable.
 * - `targetReference`: The reference genome or target used for alignment.
 * - `createdAt`: A timestamp of when this sequence file entry was created.
 * - `updatedAt`: An optional timestamp of when the file entry was last updated.
 *
 * The `*` projection defines the mapping between the table columns and the `SequenceFile` case class.
 */
class SequenceFilesTable(tag: Tag) extends Table[SequenceFile](tag, "sequence_file") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def libraryId = column[Int]("library_id")

  def fileName = column[String]("file_name")

  def fileSizeBytes = column[Long]("file_size_bytes")

  def fileMd5 = column[String]("file_md5")

  def fileFormat = column[String]("file_format")

  def aligner = column[String]("aligner")

  def targetReference = column[String]("target_reference")

  def createdAt = column[LocalDateTime]("created_at")

  def updatedAt = column[Option[LocalDateTime]]("updated_at")

  def * = (id.?, libraryId, fileName, fileSizeBytes, fileMd5, fileFormat, aligner, targetReference, createdAt, updatedAt).mapTo[SequenceFile]
}
