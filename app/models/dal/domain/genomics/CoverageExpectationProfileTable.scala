package models.dal.domain.genomics

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.CoverageExpectationProfile
import slick.lifted.{ProvenShape, Tag}

import java.time.LocalDateTime

class CoverageExpectationProfileTable(tag: Tag)
  extends MyPostgresProfile.api.Table[CoverageExpectationProfile](tag, "coverage_expectation_profile") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def testTypeId = column[Int]("test_type_id")
  def contigName = column[String]("contig_name")
  def variantClass = column[String]("variant_class")
  def minDepthHigh = column[Double]("min_depth_high")
  def minDepthMedium = column[Double]("min_depth_medium")
  def minDepthLow = column[Double]("min_depth_low")
  def minCoveragePct = column[Option[Double]]("min_coverage_pct")
  def minMappingQuality = column[Option[Double]]("min_mapping_quality")
  def minCallablePct = column[Option[Double]]("min_callable_pct")
  def notes = column[Option[String]]("notes")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  override def * : ProvenShape[CoverageExpectationProfile] = (
    id.?,
    testTypeId,
    contigName,
    variantClass,
    minDepthHigh,
    minDepthMedium,
    minDepthLow,
    minCoveragePct,
    minMappingQuality,
    minCallablePct,
    notes,
    createdAt,
    updatedAt
  ).mapTo[CoverageExpectationProfile]
}
