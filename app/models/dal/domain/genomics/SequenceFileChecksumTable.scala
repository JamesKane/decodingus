package models.dal.domain.genomics

import models.domain.genomics.SequenceFileChecksum
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime

class SequenceFileChecksumTable(tag: Tag) extends Table[SequenceFileChecksum](tag, "sequence_file_checksum") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def sequenceFileId = column[Int]("sequence_file_id")

  def checksum = column[String]("checksum")

  def algorithm = column[String]("algorithm")

  def verifiedAt = column[LocalDateTime]("verified_at")

  def sequenceFileFk = foreignKey(
    "fk_sequence_file_checksum_sequence_file",
    sequenceFileId,
    TableQuery[SequenceFilesTable])(_.id)

  def uniqueChecksum = index(
    "idx_unique_sequence_file_algorithm",
    (sequenceFileId, algorithm),
    unique = true
  )

  def * = (id.?, sequenceFileId, checksum, algorithm, verifiedAt).mapTo[SequenceFileChecksum]
}
