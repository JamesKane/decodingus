package models.dal.domain.genomics

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.TestTypeTargetRegion
import slick.lifted.{ProvenShape, Tag}

class TestTypeTargetRegionTable(tag: Tag)
  extends MyPostgresProfile.api.Table[TestTypeTargetRegion](tag, "test_type_target_region") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def testTypeId = column[Int]("test_type_id")
  def contigName = column[String]("contig_name")
  def startPosition = column[Option[Int]]("start_position")
  def endPosition = column[Option[Int]]("end_position")
  def regionName = column[String]("region_name")
  def regionType = column[String]("region_type")
  def expectedCoveragePct = column[Option[Double]]("expected_coverage_pct")
  def expectedMinDepth = column[Option[Double]]("expected_min_depth")

  override def * : ProvenShape[TestTypeTargetRegion] = (
    id.?,
    testTypeId,
    contigName,
    startPosition,
    endPosition,
    regionName,
    regionType,
    expectedCoveragePct,
    expectedMinDepth
  ).mapTo[TestTypeTargetRegion]
}
