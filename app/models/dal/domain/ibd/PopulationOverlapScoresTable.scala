package models.dal.domain.ibd

import models.dal.MyPostgresProfile.api.*
import models.domain.ibd.PopulationOverlapScore

import java.time.ZonedDateTime
import java.util.UUID

class PopulationOverlapScoresTable(tag: Tag) extends Table[PopulationOverlapScore](tag, "population_overlap_score") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def sampleGuid1 = column[UUID]("sample_guid_1")
  def sampleGuid2 = column[UUID]("sample_guid_2")
  def overlapScore = column[Double]("overlap_score")
  def computedAt = column[ZonedDateTime]("computed_at")

  def * = (
    id.?,
    sampleGuid1,
    sampleGuid2,
    overlapScore,
    computedAt
  ).mapTo[PopulationOverlapScore]
}
