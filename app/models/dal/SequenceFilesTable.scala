package models.dal

import models.SequenceFile
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime

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
