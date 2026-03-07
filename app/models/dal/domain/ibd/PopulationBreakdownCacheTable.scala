package models.dal.domain.ibd

import models.dal.MyPostgresProfile.api.*
import models.domain.ibd.PopulationBreakdownCache
import play.api.libs.json.JsValue

import java.time.ZonedDateTime
import java.util.UUID

class PopulationBreakdownCacheTable(tag: Tag) extends Table[PopulationBreakdownCache](tag, "population_breakdown_cache") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def sampleGuid = column[UUID]("sample_guid")
  def breakdown = column[JsValue]("breakdown")
  def breakdownHash = column[String]("breakdown_hash")
  def cachedAt = column[ZonedDateTime]("cached_at")
  def sourceAtUri = column[Option[String]]("source_at_uri")

  def * = (
    id.?,
    sampleGuid,
    breakdown,
    breakdownHash,
    cachedAt,
    sourceAtUri
  ).mapTo[PopulationBreakdownCache]
}
