package models.dal.domain.pds

import models.dal.MyPostgresProfile.api.*
import models.domain.pds.PdsHeartbeatLog
import play.api.libs.json.JsValue

import java.time.LocalDateTime

class PdsHeartbeatLogTable(tag: Tag) extends Table[PdsHeartbeatLog](tag, "pds_heartbeat_log") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def pdsNodeId = column[Int]("pds_node_id")
  def status = column[String]("status")
  def softwareVersion = column[Option[String]]("software_version")
  def loadMetrics = column[Option[JsValue]]("load_metrics")
  def processingQueueSize = column[Option[Int]]("processing_queue_size")
  def errorMessage = column[Option[String]]("error_message")
  def recordedAt = column[LocalDateTime]("recorded_at")

  def * = (
    id.?, pdsNodeId, status, softwareVersion, loadMetrics, processingQueueSize, errorMessage, recordedAt
  ).mapTo[PdsHeartbeatLog]

  def nodeFk = foreignKey("pds_heartbeat_log_node_fk", pdsNodeId, TableQuery[PdsNodeTable])(_.id)
}
