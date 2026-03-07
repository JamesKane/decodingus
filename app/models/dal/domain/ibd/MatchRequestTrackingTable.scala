package models.dal.domain.ibd

import models.dal.MyPostgresProfile.api.*
import models.domain.ibd.MatchRequestTracking

import java.time.ZonedDateTime
import java.util.UUID

class MatchRequestTrackingTable(tag: Tag) extends Table[MatchRequestTracking](tag, "match_request_tracking") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def atUri = column[String]("at_uri")
  def requesterDid = column[String]("requester_did")
  def fromSampleGuid = column[UUID]("from_sample_guid")
  def toSampleGuid = column[UUID]("to_sample_guid")
  def status = column[String]("status")
  def message = column[Option[String]]("message")
  def createdAt = column[ZonedDateTime]("created_at")
  def updatedAt = column[ZonedDateTime]("updated_at")
  def expiresAt = column[Option[ZonedDateTime]]("expires_at")

  def * = (
    id.?,
    atUri,
    requesterDid,
    fromSampleGuid,
    toSampleGuid,
    status,
    message,
    createdAt,
    updatedAt,
    expiresAt
  ).mapTo[MatchRequestTracking]
}
