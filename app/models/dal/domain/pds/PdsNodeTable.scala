package models.dal.domain.pds

import models.dal.MyPostgresProfile.api.*
import models.domain.pds.PdsNode
import play.api.libs.json.JsValue

import java.time.LocalDateTime

class PdsNodeTable(tag: Tag) extends Table[PdsNode](tag, "pds_node") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def did = column[String]("did")
  def pdsUrl = column[String]("pds_url")
  def handle = column[Option[String]]("handle")
  def nodeName = column[Option[String]]("node_name")
  def softwareVersion = column[Option[String]]("software_version")
  def status = column[String]("status")
  def capabilities = column[JsValue]("capabilities")
  def lastHeartbeat = column[Option[LocalDateTime]]("last_heartbeat")
  def lastCommitCid = column[Option[String]]("last_commit_cid")
  def lastCommitRev = column[Option[String]]("last_commit_rev")
  def ipAddress = column[Option[String]]("ip_address")
  def osInfo = column[Option[String]]("os_info")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  def * = (
    id.?, did, pdsUrl, handle, nodeName, softwareVersion, status, capabilities,
    lastHeartbeat, lastCommitCid, lastCommitRev, ipAddress, osInfo, createdAt, updatedAt
  ).mapTo[PdsNode]

  def uniqueDid = index("idx_pds_node_did_unique", did, unique = true)
}
