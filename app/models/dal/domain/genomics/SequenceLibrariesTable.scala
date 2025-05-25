package models.dal.domain.genomics

import models.domain.genomics.SequenceLibrary
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents the `sequence_library` table in the database, providing mappings and schema definitions for sequencing library records.
 *
 * A sequencing library is a collection of sequence data generated during a sequencing experiment, along with its associated metadata.
 * This table stores information such as the laboratory that generated the data, the sequencing instrument used, the type of test,
 * and various sequencing parameters (e.g., read count, read length, paired-end status).
 *
 * Table columns:
 * - `id`: A unique identifier for the sequencing library (primary key, auto-incremented).
 * - `sample_guid`: A UUID representing the sample associated with this sequencing library.
 * - `lab`: The name of the laboratory responsible for creating or processing the sequencing data.
 * - `test_type`: The type of sequencing test performed (e.g., WGS, RNA-Seq).
 * - `run_date`: The timestamp when the sequencing run occurred.
 * - `instrument`: The name or model of the sequencing instrument used for generating the data.
 * - `reads`: The total number of sequencing reads generated in this library.
 * - `read_length`: The length of each read in base pairs.
 * - `paired_end`: A boolean indicating whether the sequencing was paired-end (true) or single-end (false).
 * - `insert_size`: Median insert size for paired-end sequencing (null if not applicable).
 * - `created_at`: The timestamp when the record was created.
 * - `updated_at`: An optional timestamp indicating when the record was last updated.
 *
 * The data in this table is mapped to the `SequenceLibrary` case class. The case class encapsulates the sequencing library's
 * metadata, making it easier to access and manipulate within the application.
 *
 * Relationships:
 * This table may be linked to other tables (e.g., samples or sequencing experiments) via the `sample_guid` column.
 */
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
