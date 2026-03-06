package models.dal.domain.genomics

import models.domain.genomics.BiosampleCallableLoci
import models.dal.MyPostgresProfile.api.*

import java.time.LocalDateTime
import java.util.UUID

class BiosampleCallableLociTable(tag: Tag) extends Table[BiosampleCallableLoci](tag, Some("genomics"), "biosample_callable_loci") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def sampleType = column[String]("sample_type")
  def sampleId = column[Int]("sample_id")
  def sampleGuid = column[Option[UUID]]("sample_guid")
  def chromosome = column[String]("chromosome")
  def totalCallableBp = column[Long]("total_callable_bp")
  def regionCount = column[Option[Int]]("region_count")
  def bedFileHash = column[Option[String]]("bed_file_hash")
  def computedAt = column[LocalDateTime]("computed_at")
  def sourceTestTypeId = column[Option[Int]]("source_test_type_id")
  def yXdegenCallableBp = column[Option[Long]]("y_xdegen_callable_bp")
  def yAmpliconicCallableBp = column[Option[Long]]("y_ampliconic_callable_bp")
  def yPalindromicCallableBp = column[Option[Long]]("y_palindromic_callable_bp")

  def * = (
    id.?, sampleType, sampleId, sampleGuid, chromosome, totalCallableBp,
    regionCount, bedFileHash, computedAt, sourceTestTypeId,
    yXdegenCallableBp, yAmpliconicCallableBp, yPalindromicCallableBp
  ).mapTo[BiosampleCallableLoci]
}
