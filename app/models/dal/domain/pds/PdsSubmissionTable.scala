package models.dal.domain.pds

import models.dal.MyPostgresProfile.api.*
import models.domain.pds.PdsSubmission
import play.api.libs.json.JsValue

import java.time.LocalDateTime
import java.util.UUID

class PdsSubmissionTable(tag: Tag) extends Table[PdsSubmission](tag, "pds_submission") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def pdsNodeId = column[Int]("pds_node_id")
  def submissionType = column[String]("submission_type")
  def biosampleId = column[Option[Int]]("biosample_id")
  def biosampleGuid = column[Option[UUID]]("biosample_guid")
  def proposedValue = column[String]("proposed_value")
  def confidenceScore = column[Option[Double]]("confidence_score")
  def algorithmVersion = column[Option[String]]("algorithm_version")
  def softwareVersion = column[Option[String]]("software_version")
  def payload = column[Option[JsValue]]("payload")
  def status = column[String]("status")
  def reviewedBy = column[Option[String]]("reviewed_by")
  def reviewedAt = column[Option[LocalDateTime]]("reviewed_at")
  def reviewNotes = column[Option[String]]("review_notes")
  def atUri = column[Option[String]]("at_uri")
  def atCid = column[Option[String]]("at_cid")
  def createdAt = column[LocalDateTime]("created_at")

  def * = (
    id.?, pdsNodeId, submissionType, biosampleId, biosampleGuid, proposedValue,
    confidenceScore, algorithmVersion, softwareVersion, payload, status,
    reviewedBy, reviewedAt, reviewNotes, atUri, atCid, createdAt
  ).mapTo[PdsSubmission]

  def nodeFk = foreignKey("pds_submission_node_fk", pdsNodeId, TableQuery[PdsNodeTable])(_.id)
}
