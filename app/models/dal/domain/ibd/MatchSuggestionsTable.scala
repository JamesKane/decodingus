package models.dal.domain.ibd

import models.dal.MyPostgresProfile.api.*
import models.domain.ibd.MatchSuggestion
import play.api.libs.json.JsValue

import java.time.ZonedDateTime
import java.util.UUID

class MatchSuggestionsTable(tag: Tag) extends Table[MatchSuggestion](tag, "match_suggestion") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def targetSampleGuid = column[UUID]("target_sample_guid")
  def suggestedSampleGuid = column[UUID]("suggested_sample_guid")
  def suggestionType = column[String]("suggestion_type")
  def score = column[Double]("score")
  def metadata = column[Option[JsValue]]("metadata")
  def status = column[String]("status")
  def createdAt = column[ZonedDateTime]("created_at")
  def expiresAt = column[Option[ZonedDateTime]]("expires_at")

  def * = (
    id.?,
    targetSampleGuid,
    suggestedSampleGuid,
    suggestionType,
    score,
    metadata,
    status,
    createdAt,
    expiresAt
  ).mapTo[MatchSuggestion]
}
