package models.domain.pds

import play.api.libs.json.{Json, JsValue, OFormat}

import java.time.LocalDateTime

case class PdsNode(
                    id: Option[Int] = None,
                    did: String,
                    pdsUrl: String,
                    handle: Option[String] = None,
                    nodeName: Option[String] = None,
                    softwareVersion: Option[String] = None,
                    status: String = "UNKNOWN",
                    capabilities: JsValue = Json.obj(),
                    lastHeartbeat: Option[LocalDateTime] = None,
                    lastCommitCid: Option[String] = None,
                    lastCommitRev: Option[String] = None,
                    ipAddress: Option[String] = None,
                    osInfo: Option[String] = None,
                    createdAt: LocalDateTime = LocalDateTime.now(),
                    updatedAt: LocalDateTime = LocalDateTime.now()
                  )

object PdsNode {
  implicit val format: OFormat[PdsNode] = Json.format[PdsNode]

  val ValidStatuses: Set[String] = Set("ONLINE", "OFFLINE", "BUSY", "ERROR", "UNKNOWN")
}

case class PdsHeartbeatLog(
                            id: Option[Int] = None,
                            pdsNodeId: Int,
                            status: String,
                            softwareVersion: Option[String] = None,
                            loadMetrics: Option[JsValue] = None,
                            processingQueueSize: Option[Int] = Some(0),
                            errorMessage: Option[String] = None,
                            recordedAt: LocalDateTime = LocalDateTime.now()
                          )

object PdsHeartbeatLog {
  implicit val format: OFormat[PdsHeartbeatLog] = Json.format[PdsHeartbeatLog]
}

case class PdsFleetConfig(
                           id: Option[Int] = None,
                           configKey: String,
                           configValue: String,
                           description: Option[String] = None,
                           updatedBy: Option[String] = None,
                           updatedAt: LocalDateTime = LocalDateTime.now()
                         )

object PdsFleetConfig {
  implicit val format: OFormat[PdsFleetConfig] = Json.format[PdsFleetConfig]
}

case class PdsFleetSummary(
                            totalNodes: Int,
                            onlineNodes: Int,
                            offlineNodes: Int,
                            busyNodes: Int,
                            errorNodes: Int,
                            unknownNodes: Int,
                            targetVersion: Option[String],
                            nodesOnTargetVersion: Int,
                            nodesOutdated: Int
                          )

object PdsFleetSummary {
  implicit val format: OFormat[PdsFleetSummary] = Json.format[PdsFleetSummary]
}
