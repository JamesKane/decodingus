package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api._
import models.domain.genomics.SequenceFileChecksum

import java.time.LocalDateTime

class SequenceFileChecksumsTable(tag: Tag) extends Table[SequenceFileChecksum](tag, "sequence_file_checksum") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def sequenceFileId = column[Int]("sequence_file_id")
  def checksum = column[String]("checksum")
  def algorithm = column[String]("algorithm")
  def verifiedAt = column[LocalDateTime]("verified_at")

  def * = (id.?, sequenceFileId, checksum, algorithm, verifiedAt).mapTo[SequenceFileChecksum]

  def sequenceFile = foreignKey("sfc_sequence_file_fk", sequenceFileId, TableQuery[SequenceFilesTable])(_.id, onDelete = ForeignKeyAction.Cascade)
}
