package services

import helpers.ServiceSpec
import models.domain.pds.*
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{never, reset, verify, when}
import play.api.libs.json.Json
import repositories.{PdsFleetConfigRepository, PdsHeartbeatLogRepository, PdsNodeRepository}

import java.time.LocalDateTime
import scala.concurrent.Future

class PdsFleetServiceSpec extends ServiceSpec {

  val mockNodeRepo: PdsNodeRepository = mock[PdsNodeRepository]
  val mockHeartbeatRepo: PdsHeartbeatLogRepository = mock[PdsHeartbeatLogRepository]
  val mockConfigRepo: PdsFleetConfigRepository = mock[PdsFleetConfigRepository]

  val service = new PdsFleetService(mockNodeRepo, mockHeartbeatRepo, mockConfigRepo)

  override def beforeEach(): Unit = {
    reset(mockNodeRepo, mockHeartbeatRepo, mockConfigRepo)
  }

  val now: LocalDateTime = LocalDateTime.now()

  val testNode: PdsNode = PdsNode(
    id = Some(1),
    did = "did:plc:user1",
    pdsUrl = "https://pds.user1.example.com",
    handle = Some("user1.example.com"),
    nodeName = Some("User1 PDS"),
    softwareVersion = Some("0.1.0"),
    status = "ONLINE",
    capabilities = Json.obj("haplogroup_analysis" -> true, "str_analysis" -> false),
    lastHeartbeat = Some(now),
    createdAt = now,
    updatedAt = now
  )

  val testNode2: PdsNode = PdsNode(
    id = Some(2),
    did = "did:plc:user2",
    pdsUrl = "https://pds.user2.example.com",
    softwareVersion = Some("0.0.9"),
    status = "OFFLINE",
    createdAt = now,
    updatedAt = now
  )

  val targetVersionConfig: PdsFleetConfig = PdsFleetConfig(
    id = Some(1), configKey = "target_software_version", configValue = "0.1.0"
  )

  val offlineThresholdConfig: PdsFleetConfig = PdsFleetConfig(
    id = Some(3), configKey = "offline_threshold_seconds", configValue = "900"
  )

  "PdsFleetService.processHeartbeat" should {

    "register new node on first heartbeat" in {
      val request = HeartbeatRequest(
        did = "did:plc:newuser",
        pdsUrl = "https://pds.newuser.example.com",
        handle = Some("newuser.example.com"),
        softwareVersion = Some("0.1.0"),
        status = "ONLINE"
      )

      when(mockNodeRepo.findByDid("did:plc:newuser")).thenReturn(Future.successful(None))
      when(mockNodeRepo.create(any[PdsNode])).thenReturn(
        Future.successful(PdsNode(id = Some(10), did = "did:plc:newuser",
          pdsUrl = "https://pds.newuser.example.com", status = "ONLINE")))
      when(mockHeartbeatRepo.create(any[PdsHeartbeatLog]))
        .thenReturn(Future.successful(PdsHeartbeatLog(id = Some(1), pdsNodeId = 10, status = "ONLINE")))

      val result = service.processHeartbeat(request).futureValue
      result.isRight mustBe true
      result.toOption.get.did mustBe "did:plc:newuser"

      verify(mockNodeRepo).create(any[PdsNode])
      verify(mockHeartbeatRepo).create(any[PdsHeartbeatLog])
    }

    "update existing node on subsequent heartbeat" in {
      val request = HeartbeatRequest(
        did = "did:plc:user1",
        pdsUrl = "https://pds.user1.example.com",
        softwareVersion = Some("0.1.1"),
        status = "ONLINE",
        lastCommitCid = Some("bafyabc123")
      )

      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockNodeRepo.updateHeartbeat(meq(1), meq("ONLINE"), meq(Some("0.1.1")),
        meq(Some("bafyabc123")), any[Option[String]])).thenReturn(Future.successful(true))
      when(mockNodeRepo.update(any[PdsNode])).thenReturn(Future.successful(true))
      when(mockNodeRepo.findById(1)).thenReturn(Future.successful(Some(testNode.copy(softwareVersion = Some("0.1.1")))))
      when(mockHeartbeatRepo.create(any[PdsHeartbeatLog]))
        .thenReturn(Future.successful(PdsHeartbeatLog(id = Some(2), pdsNodeId = 1, status = "ONLINE")))

      val result = service.processHeartbeat(request).futureValue
      result.isRight mustBe true

      verify(mockNodeRepo).updateHeartbeat(meq(1), meq("ONLINE"), meq(Some("0.1.1")),
        meq(Some("bafyabc123")), any[Option[String]])
      verify(mockNodeRepo, never()).create(any[PdsNode])
    }

    "reject invalid status" in {
      val request = HeartbeatRequest(
        did = "did:plc:user1", pdsUrl = "https://example.com", status = "INVALID"
      )

      val result = service.processHeartbeat(request).futureValue
      result mustBe Left("Invalid status: INVALID")
    }

    "record error heartbeat with message" in {
      val request = HeartbeatRequest(
        did = "did:plc:user1",
        pdsUrl = "https://pds.user1.example.com",
        status = "ERROR",
        errorMessage = Some("Disk full")
      )

      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockNodeRepo.updateHeartbeat(meq(1), meq("ERROR"), any(), any(), any()))
        .thenReturn(Future.successful(true))
      when(mockNodeRepo.update(any[PdsNode])).thenReturn(Future.successful(true))
      when(mockNodeRepo.findById(1)).thenReturn(Future.successful(Some(testNode.copy(status = "ERROR"))))
      when(mockHeartbeatRepo.create(any[PdsHeartbeatLog]))
        .thenReturn(Future.successful(PdsHeartbeatLog(id = Some(3), pdsNodeId = 1, status = "ERROR",
          errorMessage = Some("Disk full"))))

      val result = service.processHeartbeat(request).futureValue
      result.isRight mustBe true
      result.toOption.get.status mustBe "ERROR"
    }
  }

  "PdsFleetService.getFleetSummary" should {

    "compute fleet summary with status counts and version compliance" in {
      when(mockNodeRepo.countByStatus()).thenReturn(Future.successful(
        Map("ONLINE" -> 5, "OFFLINE" -> 2, "BUSY" -> 1, "ERROR" -> 1)
      ))
      when(mockConfigRepo.findByKey("target_software_version"))
        .thenReturn(Future.successful(Some(targetVersionConfig)))
      when(mockNodeRepo.findAll()).thenReturn(Future.successful(Seq(
        testNode.copy(softwareVersion = Some("0.1.0")),
        testNode2.copy(softwareVersion = Some("0.0.9")),
        testNode.copy(id = Some(3), did = "did:plc:user3", softwareVersion = Some("0.1.0")),
        testNode.copy(id = Some(4), did = "did:plc:user4", softwareVersion = Some("0.1.0")),
        testNode.copy(id = Some(5), did = "did:plc:user5", softwareVersion = Some("0.1.0")),
        testNode.copy(id = Some(6), did = "did:plc:user6", softwareVersion = Some("0.0.9")),
        testNode.copy(id = Some(7), did = "did:plc:user7", softwareVersion = Some("0.1.0")),
        testNode.copy(id = Some(8), did = "did:plc:user8", softwareVersion = Some("0.1.0")),
        testNode.copy(id = Some(9), did = "did:plc:user9", softwareVersion = Some("0.1.0"))
      )))

      val summary = service.getFleetSummary.futureValue
      summary.totalNodes mustBe 9
      summary.onlineNodes mustBe 5
      summary.offlineNodes mustBe 2
      summary.busyNodes mustBe 1
      summary.errorNodes mustBe 1
      summary.targetVersion mustBe Some("0.1.0")
      summary.nodesOnTargetVersion mustBe 7
      summary.nodesOutdated mustBe 2
    }

    "handle empty fleet" in {
      when(mockNodeRepo.countByStatus()).thenReturn(Future.successful(Map.empty))
      when(mockConfigRepo.findByKey("target_software_version")).thenReturn(Future.successful(None))
      when(mockNodeRepo.findAll()).thenReturn(Future.successful(Seq.empty))

      val summary = service.getFleetSummary.futureValue
      summary.totalNodes mustBe 0
      summary.onlineNodes mustBe 0
      summary.targetVersion mustBe None
    }
  }

  "PdsFleetService.markStaleNodesOffline" should {

    "mark nodes without recent heartbeat as offline" in {
      val staleNode = testNode.copy(
        id = Some(5), status = "ONLINE",
        lastHeartbeat = Some(now.minusMinutes(20))
      )

      when(mockConfigRepo.findByKey("offline_threshold_seconds"))
        .thenReturn(Future.successful(Some(offlineThresholdConfig)))
      when(mockNodeRepo.findStaleNodes(any[LocalDateTime])).thenReturn(Future.successful(Seq(staleNode)))
      when(mockNodeRepo.updateStatus(5, "OFFLINE")).thenReturn(Future.successful(true))

      val count = service.markStaleNodesOffline().futureValue
      count mustBe 1
      verify(mockNodeRepo).updateStatus(5, "OFFLINE")
    }

    "use default threshold when config is missing" in {
      when(mockConfigRepo.findByKey("offline_threshold_seconds"))
        .thenReturn(Future.successful(None))
      when(mockNodeRepo.findStaleNodes(any[LocalDateTime])).thenReturn(Future.successful(Seq.empty))

      val count = service.markStaleNodesOffline().futureValue
      count mustBe 0
    }
  }

  "PdsFleetService.listNodes" should {

    "list all nodes without filter" in {
      when(mockNodeRepo.findAll()).thenReturn(Future.successful(Seq(testNode, testNode2)))

      val result = service.listNodes().futureValue
      result.size mustBe 2
    }

    "filter nodes by status" in {
      when(mockNodeRepo.findByStatus("ONLINE")).thenReturn(Future.successful(Seq(testNode)))

      val result = service.listNodes(Some("ONLINE")).futureValue
      result.size mustBe 1
      result.head.status mustBe "ONLINE"
    }
  }

  "PdsFleetService.getNode" should {

    "return node by DID" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))

      val result = service.getNode("did:plc:user1").futureValue
      result mustBe defined
      result.get.pdsUrl mustBe "https://pds.user1.example.com"
    }

    "return None for unknown DID" in {
      when(mockNodeRepo.findByDid("did:plc:unknown")).thenReturn(Future.successful(None))

      val result = service.getNode("did:plc:unknown").futureValue
      result mustBe None
    }
  }

  "PdsFleetService.updateFleetConfig" should {

    "update existing config" in {
      when(mockConfigRepo.upsert("target_software_version", "0.2.0", Some("curator1")))
        .thenReturn(Future.successful(true))

      val result = service.updateFleetConfig("target_software_version", "0.2.0", Some("curator1")).futureValue
      result mustBe Right(true)
    }
  }

  "PdsFleetService.removeNode" should {

    "remove existing node" in {
      when(mockNodeRepo.findByDid("did:plc:user1")).thenReturn(Future.successful(Some(testNode)))
      when(mockNodeRepo.delete(1)).thenReturn(Future.successful(true))

      val result = service.removeNode("did:plc:user1").futureValue
      result mustBe Right(true)
    }

    "return error for unknown node" in {
      when(mockNodeRepo.findByDid("did:plc:unknown")).thenReturn(Future.successful(None))

      val result = service.removeNode("did:plc:unknown").futureValue
      result mustBe Left("Node not found")
    }
  }

  "PdsFleetService.getNodeHeartbeatHistory" should {

    "return heartbeat history for a node" in {
      val logs = Seq(
        PdsHeartbeatLog(id = Some(1), pdsNodeId = 1, status = "ONLINE", recordedAt = now),
        PdsHeartbeatLog(id = Some(2), pdsNodeId = 1, status = "BUSY", recordedAt = now.minusMinutes(5))
      )
      when(mockHeartbeatRepo.findByNode(1, 100)).thenReturn(Future.successful(logs))

      val result = service.getNodeHeartbeatHistory(1).futureValue
      result.size mustBe 2
    }
  }

  "PdsFleetService.pruneHeartbeatLogs" should {

    "delete old heartbeat logs" in {
      when(mockHeartbeatRepo.deleteOlderThan(any[LocalDateTime])).thenReturn(Future.successful(150))

      val result = service.pruneHeartbeatLogs(30).futureValue
      result mustBe 150
    }
  }
}
