package services

import jakarta.inject.{Inject, Singleton}
import models.domain.pds.*
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import repositories.{PdsFleetConfigRepository, PdsHeartbeatLogRepository, PdsNodeRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

case class HeartbeatRequest(
                             did: String,
                             pdsUrl: String,
                             handle: Option[String] = None,
                             nodeName: Option[String] = None,
                             softwareVersion: Option[String] = None,
                             status: String = "ONLINE",
                             capabilities: Option[JsValue] = None,
                             loadMetrics: Option[JsValue] = None,
                             processingQueueSize: Option[Int] = None,
                             lastCommitCid: Option[String] = None,
                             lastCommitRev: Option[String] = None,
                             ipAddress: Option[String] = None,
                             osInfo: Option[String] = None,
                             errorMessage: Option[String] = None
                           )

@Singleton
class PdsFleetService @Inject()(
                                 nodeRepo: PdsNodeRepository,
                                 heartbeatRepo: PdsHeartbeatLogRepository,
                                 configRepo: PdsFleetConfigRepository
                               )(implicit ec: ExecutionContext) extends Logging {

  def processHeartbeat(request: HeartbeatRequest): Future[Either[String, PdsNode]] = {
    if (!PdsNode.ValidStatuses.contains(request.status))
      return Future.successful(Left(s"Invalid status: ${request.status}"))

    nodeRepo.findByDid(request.did).flatMap {
      case Some(existing) =>
        for {
          _ <- nodeRepo.updateHeartbeat(
            existing.id.get, request.status, request.softwareVersion,
            request.lastCommitCid, request.lastCommitRev
          )
          _ <- updateNodeFields(existing, request)
          _ <- recordHeartbeat(existing.id.get, request)
          updated <- nodeRepo.findById(existing.id.get)
        } yield Right(updated.getOrElse(existing))

      case None =>
        val newNode = PdsNode(
          did = request.did,
          pdsUrl = request.pdsUrl,
          handle = request.handle,
          nodeName = request.nodeName,
          softwareVersion = request.softwareVersion,
          status = request.status,
          capabilities = request.capabilities.getOrElse(Json.obj()),
          lastHeartbeat = Some(LocalDateTime.now()),
          lastCommitCid = request.lastCommitCid,
          lastCommitRev = request.lastCommitRev,
          ipAddress = request.ipAddress,
          osInfo = request.osInfo
        )
        for {
          created <- nodeRepo.create(newNode)
          _ <- recordHeartbeat(created.id.get, request)
        } yield Right(created)
    }
  }

  def getNode(did: String): Future[Option[PdsNode]] =
    nodeRepo.findByDid(did)

  def getNodeById(id: Int): Future[Option[PdsNode]] =
    nodeRepo.findById(id)

  def listNodes(statusFilter: Option[String] = None): Future[Seq[PdsNode]] =
    statusFilter match {
      case Some(status) => nodeRepo.findByStatus(status)
      case None => nodeRepo.findAll()
    }

  def getFleetSummary: Future[PdsFleetSummary] = {
    for {
      statusCounts <- nodeRepo.countByStatus()
      targetVersion <- configRepo.findByKey("target_software_version")
      allNodes <- nodeRepo.findAll()
    } yield {
      val target = targetVersion.map(_.configValue)
      val onTarget = target match {
        case Some(tv) => allNodes.count(_.softwareVersion.contains(tv))
        case None => 0
      }
      val total = statusCounts.values.sum

      PdsFleetSummary(
        totalNodes = total,
        onlineNodes = statusCounts.getOrElse("ONLINE", 0),
        offlineNodes = statusCounts.getOrElse("OFFLINE", 0),
        busyNodes = statusCounts.getOrElse("BUSY", 0),
        errorNodes = statusCounts.getOrElse("ERROR", 0),
        unknownNodes = statusCounts.getOrElse("UNKNOWN", 0),
        targetVersion = target,
        nodesOnTargetVersion = onTarget,
        nodesOutdated = total - onTarget
      )
    }
  }

  def markStaleNodesOffline(): Future[Int] = {
    configRepo.findByKey("offline_threshold_seconds").flatMap { configOpt =>
      val thresholdSeconds = configOpt.map(_.configValue.toLong).getOrElse(900L)
      val cutoff = LocalDateTime.now().minusSeconds(thresholdSeconds)
      nodeRepo.findStaleNodes(cutoff).flatMap { staleNodes =>
        Future.sequence(staleNodes.flatMap(_.id).map { nodeId =>
          nodeRepo.updateStatus(nodeId, "OFFLINE")
        }).map(_.count(identity))
      }
    }
  }

  def getNodeHeartbeatHistory(nodeId: Int, limit: Int = 100): Future[Seq[PdsHeartbeatLog]] =
    heartbeatRepo.findByNode(nodeId, limit)

  def getFleetConfig: Future[Seq[PdsFleetConfig]] =
    configRepo.findAll()

  def updateFleetConfig(key: String, value: String, updatedBy: Option[String] = None): Future[Either[String, Boolean]] = {
    configRepo.upsert(key, value, updatedBy).map { success =>
      if (success) Right(true)
      else Left("Failed to update fleet config")
    }
  }

  def removeNode(did: String): Future[Either[String, Boolean]] = {
    nodeRepo.findByDid(did).flatMap {
      case None => Future.successful(Left("Node not found"))
      case Some(node) =>
        nodeRepo.delete(node.id.get).map(Right(_))
    }
  }

  def pruneHeartbeatLogs(retentionDays: Int = 30): Future[Int] = {
    val cutoff = LocalDateTime.now().minusDays(retentionDays)
    heartbeatRepo.deleteOlderThan(cutoff)
  }

  private def updateNodeFields(existing: PdsNode, request: HeartbeatRequest): Future[Boolean] = {
    val updated = existing.copy(
      pdsUrl = request.pdsUrl,
      handle = request.handle.orElse(existing.handle),
      nodeName = request.nodeName.orElse(existing.nodeName),
      softwareVersion = request.softwareVersion.orElse(existing.softwareVersion),
      status = request.status,
      capabilities = request.capabilities.getOrElse(existing.capabilities),
      ipAddress = request.ipAddress.orElse(existing.ipAddress),
      osInfo = request.osInfo.orElse(existing.osInfo)
    )
    nodeRepo.update(updated)
  }

  private def recordHeartbeat(nodeId: Int, request: HeartbeatRequest): Future[PdsHeartbeatLog] = {
    val log = PdsHeartbeatLog(
      pdsNodeId = nodeId,
      status = request.status,
      softwareVersion = request.softwareVersion,
      loadMetrics = request.loadMetrics,
      processingQueueSize = request.processingQueueSize,
      errorMessage = request.errorMessage
    )
    heartbeatRepo.create(log)
  }
}
