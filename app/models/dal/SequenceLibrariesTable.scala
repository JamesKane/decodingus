package models.dal

import models.SequenceLibrary
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime
import java.util.UUID

class SequenceLibrariesTable(tag: Tag) extends Table[SequenceLibrary](tag, "sequence_library") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def sampleGuid = column[UUID]("sample_guid")

  def lab = column[String]("lab")

  def testType = column[String]("test_type")

  def runDate = column[LocalDateTime]("run_date")

  def instrument = column[String]("instrument")

  def reads = column[Int]("reads")

  def readLength = column[Int]("read_length")

  def pairedEnd = column[Boolean]("paired_end")

  def insertSize = column[Int]("insert_size")

  def createdAt = column[LocalDateTime]("created_at")

  def updatedAt = column[Option[LocalDateTime]]("updated_at")

  def * = (id.?, sampleGuid, lab, testType, runDate, instrument, reads, readLength, pairedEnd, insertSize, createdAt, updatedAt).mapTo[SequenceLibrary]
}
