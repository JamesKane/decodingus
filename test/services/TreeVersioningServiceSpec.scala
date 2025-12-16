package services

import models.HaplogroupType
import models.api.haplogroups.MergeStatistics
import models.domain.haplogroups.*
import org.mockito.ArgumentMatchers.{any, anyInt, anyString, eq => eqTo}
import org.mockito.Mockito.{never, reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, TreeVersioningRepository, WipTreeRepository, WipStatistics}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

class TreeVersioningServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  // Mocks
  var mockRepository: TreeVersioningRepository = _
  var mockWipRepository: WipTreeRepository = _
  var mockHaplogroupRepository: HaplogroupCoreRepository = _
  var mockHaplogroupVariantRepository: HaplogroupVariantRepository = _
  var mockAuditService: CuratorAuditService = _
  var service: TreeVersioningServiceImpl = _

  // Test fixtures
  val now: LocalDateTime = LocalDateTime.now()

  def createChangeSet(
    id: Int,
    haplogroupType: HaplogroupType = HaplogroupType.Y,
    name: String = "TestChangeSet",
    sourceName: String = "TestSource",
    status: ChangeSetStatus = ChangeSetStatus.Draft,
    createdBy: String = "test-user"
  ): ChangeSet = ChangeSet(
    id = Some(id),
    haplogroupType = haplogroupType,
    name = name,
    description = Some("Test description"),
    sourceName = sourceName,
    createdAt = now,
    createdBy = createdBy,
    status = status,
    statistics = ChangeSetStatistics()
  )

  def createTreeChange(
    id: Int,
    changeSetId: Int,
    changeType: TreeChangeType = TreeChangeType.Create,
    haplogroupId: Option[Int] = None,
    status: ChangeStatus = ChangeStatus.Pending
  ): TreeChange = TreeChange(
    id = Some(id),
    changeSetId = changeSetId,
    changeType = changeType,
    haplogroupId = haplogroupId,
    status = status,
    createdAt = now,
    sequenceNum = id
  )

  override def beforeEach(): Unit = {
    mockRepository = mock[TreeVersioningRepository]
    mockWipRepository = mock[WipTreeRepository]
    mockHaplogroupRepository = mock[HaplogroupCoreRepository]
    mockHaplogroupVariantRepository = mock[HaplogroupVariantRepository]
    mockAuditService = mock[CuratorAuditService]
    service = new TreeVersioningServiceImpl(
      mockRepository,
      mockWipRepository,
      mockHaplogroupRepository,
      mockHaplogroupVariantRepository,
      mockAuditService
    )

    // Default mock behavior for WIP repository (empty stats - no WIP data)
    when(mockWipRepository.getWipStatistics(anyInt()))
      .thenReturn(Future.successful(WipStatistics(0, 0, 0, 0)))

    // Default mock behavior for audit service
    when(mockAuditService.logChangeSetCreate(anyString(), any[ChangeSet], any[Option[String]]))
      .thenReturn(Future.successful(mock[models.domain.curator.AuditLogEntry]))
    when(mockAuditService.logChangeSetStatusChange(anyString(), anyInt(), any[ChangeSetStatus], any[ChangeSetStatus], any[Option[String]]))
      .thenReturn(Future.successful(mock[models.domain.curator.AuditLogEntry]))
    when(mockAuditService.logChangeSetApply(anyString(), any[ChangeSet], anyInt(), any[Option[String]]))
      .thenReturn(Future.successful(mock[models.domain.curator.AuditLogEntry]))
    when(mockAuditService.logChangeSetDiscard(anyString(), any[ChangeSet], anyString()))
      .thenReturn(Future.successful(mock[models.domain.curator.AuditLogEntry]))
    when(mockAuditService.logChangeReview(anyString(), any[TreeChange], anyString(), any[Option[String]]))
      .thenReturn(Future.successful(mock[models.domain.curator.AuditLogEntry]))
  }

  // ============================================================================
  // Change Set Lifecycle Tests
  // ============================================================================

  "TreeVersioningService.createChangeSet" should {

    "create a new change set when none exists" in {
      when(mockRepository.getActiveChangeSet(HaplogroupType.Y))
        .thenReturn(Future.successful(None))
      when(mockRepository.createChangeSet(any[ChangeSet]))
        .thenReturn(Future.successful(1))
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(createChangeSet(1))))

      whenReady(service.createChangeSet(HaplogroupType.Y, "ISOGG", Some("Test merge"))) { result =>
        result.id mustBe Some(1)
        result.haplogroupType mustBe HaplogroupType.Y
        verify(mockRepository).createChangeSet(any[ChangeSet])
        verify(mockAuditService).logChangeSetCreate(anyString(), any[ChangeSet], any[Option[String]])
      }
    }

    "fail when active change set already exists" in {
      val existingCs = createChangeSet(1, status = ChangeSetStatus.Draft)
      when(mockRepository.getActiveChangeSet(HaplogroupType.Y))
        .thenReturn(Future.successful(Some(existingCs)))

      whenReady(service.createChangeSet(HaplogroupType.Y, "ISOGG").failed) { ex =>
        ex mustBe an[IllegalStateException]
        ex.getMessage must include("Active change set already exists")
        verify(mockRepository, never()).createChangeSet(any[ChangeSet])
      }
    }

    "fail when change set is under review" in {
      val existingCs = createChangeSet(1, status = ChangeSetStatus.UnderReview)
      when(mockRepository.getActiveChangeSet(HaplogroupType.Y))
        .thenReturn(Future.successful(Some(existingCs)))

      whenReady(service.createChangeSet(HaplogroupType.Y, "ISOGG").failed) { ex =>
        ex mustBe an[IllegalStateException]
        ex.getMessage must include("UnderReview")
      }
    }
  }

  "TreeVersioningService.getActiveChangeSet" should {

    "return active change set when it exists" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.Draft)
      when(mockRepository.getActiveChangeSet(HaplogroupType.Y))
        .thenReturn(Future.successful(Some(changeSet)))

      whenReady(service.getActiveChangeSet(HaplogroupType.Y)) { result =>
        result mustBe defined
        result.get.id mustBe Some(1)
      }
    }

    "return None when no active change set" in {
      when(mockRepository.getActiveChangeSet(HaplogroupType.Y))
        .thenReturn(Future.successful(None))

      whenReady(service.getActiveChangeSet(HaplogroupType.Y)) { result =>
        result mustBe empty
      }
    }
  }

  "TreeVersioningService.getChangeSetDetails" should {

    "return full details for an existing change set" in {
      val changeSet = createChangeSet(1)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
      when(mockRepository.countTreeChanges(eqTo(1), any[Option[TreeChangeType]], any[Option[ChangeStatus]]))
        .thenReturn(Future.successful(10))
      when(mockRepository.getChangeSummaryByType(1))
        .thenReturn(Future.successful(Map(TreeChangeType.Create -> 5, TreeChangeType.Update -> 5)))
      when(mockRepository.getChangeSummaryByStatus(1))
        .thenReturn(Future.successful(Map(ChangeStatus.Pending -> 8, ChangeStatus.Applied -> 2)))
      when(mockRepository.listComments(1))
        .thenReturn(Future.successful(Seq.empty))

      whenReady(service.getChangeSetDetails(1)) { result =>
        result mustBe defined
        result.get.changeSet.id mustBe Some(1)
        result.get.totalChanges mustBe 10
        result.get.changesByType must contain key "CREATE"
        result.get.changesByStatus must contain key "PENDING"
      }
    }

    "return None for non-existent change set" in {
      when(mockRepository.getChangeSet(999))
        .thenReturn(Future.successful(None))

      whenReady(service.getChangeSetDetails(999)) { result =>
        result mustBe empty
      }
    }
  }

  "TreeVersioningService.finalizeChangeSet" should {

    "update change set with statistics and move to READY_FOR_REVIEW" in {
      val stats = MergeStatistics(
        nodesProcessed = 100,
        nodesCreated = 20,
        nodesUpdated = 30,
        nodesUnchanged = 50,
        variantsAdded = 15,
        variantsUpdated = 3,
        relationshipsCreated = 25,
        relationshipsUpdated = 5,
        splitOperations = 2
      )

      when(mockRepository.finalizeChangeSet(eqTo(1), any[ChangeSetStatistics], any[Option[String]]))
        .thenReturn(Future.successful(true))

      whenReady(service.finalizeChangeSet(1, stats, Some("/path/to/report.txt"))) { result =>
        result mustBe true
        verify(mockRepository).finalizeChangeSet(eqTo(1), any[ChangeSetStatistics], eqTo(Some("/path/to/report.txt")))
      }
    }
  }

  "TreeVersioningService.startReview" should {

    "start review when change set is READY_FOR_REVIEW" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.ReadyForReview)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
      when(mockRepository.updateChangeSetStatus(1, ChangeSetStatus.UnderReview))
        .thenReturn(Future.successful(true))

      whenReady(service.startReview(1, "curator123")) { result =>
        result mustBe true
        verify(mockRepository).updateChangeSetStatus(1, ChangeSetStatus.UnderReview)
        verify(mockAuditService).logChangeSetStatusChange(
          eqTo("curator123"), eqTo(1),
          eqTo(ChangeSetStatus.ReadyForReview), eqTo(ChangeSetStatus.UnderReview),
          any[Option[String]]
        )
      }
    }

    "fail when change set is not READY_FOR_REVIEW" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.Draft)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))

      whenReady(service.startReview(1, "curator123").failed) { ex =>
        ex mustBe an[IllegalStateException]
        ex.getMessage must include("expected READY_FOR_REVIEW")
        verify(mockRepository, never()).updateChangeSetStatus(anyInt(), any[ChangeSetStatus])
      }
    }

    "fail when change set not found" in {
      when(mockRepository.getChangeSet(999))
        .thenReturn(Future.successful(None))

      whenReady(service.startReview(999, "curator123").failed) { ex =>
        ex mustBe a[NoSuchElementException]
        ex.getMessage must include("not found")
      }
    }
  }

  "TreeVersioningService.applyChangeSet" should {

    "apply change set when UNDER_REVIEW" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.UnderReview)
      val appliedChangeSet = changeSet.copy(status = ChangeSetStatus.Applied, appliedAt = Some(now))

      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
        .thenReturn(Future.successful(Some(appliedChangeSet)))
      when(mockRepository.applyAllPendingChanges(1))
        .thenReturn(Future.successful(10))
      when(mockRepository.applyChangeSet(1, "curator123"))
        .thenReturn(Future.successful(true))

      whenReady(service.applyChangeSet(1, "curator123")) { result =>
        result mustBe true
        verify(mockRepository).applyAllPendingChanges(1)
        verify(mockRepository).applyChangeSet(1, "curator123")
        verify(mockAuditService).logChangeSetApply(eqTo("curator123"), any[ChangeSet], eqTo(10), any[Option[String]])
      }
    }

    "apply change set when READY_FOR_REVIEW (skip straight to apply)" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.ReadyForReview)
      val appliedChangeSet = changeSet.copy(status = ChangeSetStatus.Applied)

      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
        .thenReturn(Future.successful(Some(appliedChangeSet)))
      when(mockRepository.applyAllPendingChanges(1))
        .thenReturn(Future.successful(5))
      when(mockRepository.applyChangeSet(1, "curator123"))
        .thenReturn(Future.successful(true))

      whenReady(service.applyChangeSet(1, "curator123")) { result =>
        result mustBe true
      }
    }

    "fail when change set is DRAFT" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.Draft)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))

      whenReady(service.applyChangeSet(1, "curator123").failed) { ex =>
        ex mustBe an[IllegalStateException]
        ex.getMessage must include("Cannot apply")
        verify(mockRepository, never()).applyChangeSet(anyInt(), anyString())
      }
    }

    "fail when change set already APPLIED" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.Applied)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))

      whenReady(service.applyChangeSet(1, "curator123").failed) { ex =>
        ex mustBe an[IllegalStateException]
        ex.getMessage must include("Cannot apply")
      }
    }
  }

  "TreeVersioningService.discardChangeSet" should {

    "discard change set when not APPLIED" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.UnderReview)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
      when(mockRepository.discardChangeSet(1, "curator123", "Not needed"))
        .thenReturn(Future.successful(true))

      whenReady(service.discardChangeSet(1, "curator123", "Not needed")) { result =>
        result mustBe true
        verify(mockRepository).discardChangeSet(1, "curator123", "Not needed")
        verify(mockAuditService).logChangeSetDiscard("curator123", changeSet, "Not needed")
      }
    }

    "discard DRAFT change set" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.Draft)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
      when(mockRepository.discardChangeSet(1, "curator123", "Aborted"))
        .thenReturn(Future.successful(true))

      whenReady(service.discardChangeSet(1, "curator123", "Aborted")) { result =>
        result mustBe true
      }
    }

    "fail when change set already APPLIED" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.Applied)
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))

      whenReady(service.discardChangeSet(1, "curator123", "Test").failed) { ex =>
        ex mustBe an[IllegalStateException]
        ex.getMessage must include("already APPLIED")
        verify(mockRepository, never()).discardChangeSet(anyInt(), anyString(), anyString())
      }
    }
  }

  // ============================================================================
  // Change Recording Tests
  // ============================================================================

  "TreeVersioningService.recordCreate" should {

    "record a CREATE change" in {
      when(mockRepository.getNextSequenceNum(1))
        .thenReturn(Future.successful(1))
      when(mockRepository.createTreeChange(any[TreeChange]))
        .thenReturn(Future.successful(100))

      val haplogroupData = """{"name":"R1b-L21","variants":["L21"]}"""

      whenReady(service.recordCreate(1, haplogroupData, Some(50))) { changeId =>
        changeId mustBe 100
        verify(mockRepository).createTreeChange(org.mockito.ArgumentMatchers.argThat { (tc: TreeChange) =>
          tc.changeType == TreeChangeType.Create &&
          tc.haplogroupData.contains(haplogroupData) &&
          tc.newParentId.contains(50)
        })
      }
    }

    "record a CREATE change with ambiguity info" in {
      when(mockRepository.getNextSequenceNum(1))
        .thenReturn(Future.successful(2))
      when(mockRepository.createTreeChange(any[TreeChange]))
        .thenReturn(Future.successful(101))

      whenReady(service.recordCreate(
        changeSetId = 1,
        haplogroupData = "{}",
        parentId = None,
        ambiguityType = Some("MULTIPLE_MATCH"),
        ambiguityConfidence = Some(0.85)
      )) { changeId =>
        changeId mustBe 101
        verify(mockRepository).createTreeChange(org.mockito.ArgumentMatchers.argThat { (tc: TreeChange) =>
          tc.ambiguityType.contains("MULTIPLE_MATCH") &&
          tc.ambiguityConfidence.contains(0.85)
        })
      }
    }
  }

  "TreeVersioningService.recordUpdate" should {

    "record an UPDATE change" in {
      when(mockRepository.getNextSequenceNum(1))
        .thenReturn(Future.successful(3))
      when(mockRepository.createTreeChange(any[TreeChange]))
        .thenReturn(Future.successful(102))

      val oldData = """{"formedYbp":4500}"""
      val newData = """{"formedYbp":4800}"""

      whenReady(service.recordUpdate(1, 100, oldData, newData)) { changeId =>
        changeId mustBe 102
        verify(mockRepository).createTreeChange(org.mockito.ArgumentMatchers.argThat { (tc: TreeChange) =>
          tc.changeType == TreeChangeType.Update &&
          tc.haplogroupId.contains(100) &&
          tc.oldData.contains(oldData) &&
          tc.haplogroupData.contains(newData)
        })
      }
    }
  }

  "TreeVersioningService.recordReparent" should {

    "record a REPARENT change" in {
      when(mockRepository.getNextSequenceNum(1))
        .thenReturn(Future.successful(4))
      when(mockRepository.createTreeChange(any[TreeChange]))
        .thenReturn(Future.successful(103))

      whenReady(service.recordReparent(1, 100, Some(50), 60)) { changeId =>
        changeId mustBe 103
        verify(mockRepository).createTreeChange(org.mockito.ArgumentMatchers.argThat { (tc: TreeChange) =>
          tc.changeType == TreeChangeType.Reparent &&
          tc.haplogroupId.contains(100) &&
          tc.oldParentId.contains(50) &&
          tc.newParentId.contains(60)
        })
      }
    }
  }

  "TreeVersioningService.recordAddVariant" should {

    "record an ADD_VARIANT change" in {
      when(mockRepository.getNextSequenceNum(1))
        .thenReturn(Future.successful(5))
      when(mockRepository.createTreeChange(any[TreeChange]))
        .thenReturn(Future.successful(104))

      whenReady(service.recordAddVariant(1, 100, 200)) { changeId =>
        changeId mustBe 104
        verify(mockRepository).createTreeChange(org.mockito.ArgumentMatchers.argThat { (tc: TreeChange) =>
          tc.changeType == TreeChangeType.AddVariant &&
          tc.haplogroupId.contains(100) &&
          tc.variantId.contains(200)
        })
      }
    }
  }

  "TreeVersioningService.recordRemoveVariant" should {

    "record a REMOVE_VARIANT change" in {
      when(mockRepository.getNextSequenceNum(1))
        .thenReturn(Future.successful(6))
      when(mockRepository.createTreeChange(any[TreeChange]))
        .thenReturn(Future.successful(105))

      whenReady(service.recordRemoveVariant(1, 100, 200)) { changeId =>
        changeId mustBe 105
        verify(mockRepository).createTreeChange(org.mockito.ArgumentMatchers.argThat { (tc: TreeChange) =>
          tc.changeType == TreeChangeType.RemoveVariant &&
          tc.haplogroupId.contains(100) &&
          tc.variantId.contains(200)
        })
      }
    }
  }

  // ============================================================================
  // Change Review Tests
  // ============================================================================

  "TreeVersioningService.getPendingReviewChanges" should {

    "return pending changes ordered by ambiguity confidence" in {
      val changes = Seq(
        createTreeChange(1, 1, TreeChangeType.Create),
        createTreeChange(2, 1, TreeChangeType.Reparent)
      )
      when(mockRepository.getPendingReviewChanges(1, 50))
        .thenReturn(Future.successful(changes))

      whenReady(service.getPendingReviewChanges(1, 50)) { result =>
        result must have size 2
        result.head.id mustBe Some(1)
      }
    }
  }

  "TreeVersioningService.reviewChange" should {

    "approve a pending change" in {
      val change = createTreeChange(1, 1)
      when(mockRepository.getTreeChange(1))
        .thenReturn(Future.successful(Some(change)))
      when(mockRepository.reviewTreeChange(1, "curator123", Some("Looks good"), ChangeStatus.Applied))
        .thenReturn(Future.successful(true))

      whenReady(service.reviewChange(1, "curator123", ChangeStatus.Applied, Some("Looks good"))) { result =>
        result mustBe true
        verify(mockRepository).reviewTreeChange(1, "curator123", Some("Looks good"), ChangeStatus.Applied)
        verify(mockAuditService).logChangeReview("curator123", change, "APPLIED", Some("Looks good"))
      }
    }

    "skip a change" in {
      val change = createTreeChange(1, 1)
      when(mockRepository.getTreeChange(1))
        .thenReturn(Future.successful(Some(change)))
      when(mockRepository.reviewTreeChange(1, "curator123", None, ChangeStatus.Skipped))
        .thenReturn(Future.successful(true))

      whenReady(service.reviewChange(1, "curator123", ChangeStatus.Skipped)) { result =>
        result mustBe true
      }
    }

    "revert a change" in {
      val change = createTreeChange(1, 1)
      when(mockRepository.getTreeChange(1))
        .thenReturn(Future.successful(Some(change)))
      when(mockRepository.reviewTreeChange(1, "curator123", Some("Incorrect reparent"), ChangeStatus.Reverted))
        .thenReturn(Future.successful(true))

      whenReady(service.reviewChange(1, "curator123", ChangeStatus.Reverted, Some("Incorrect reparent"))) { result =>
        result mustBe true
      }
    }

    "fail when trying to set status back to PENDING" in {
      whenReady(service.reviewChange(1, "curator123", ChangeStatus.Pending).failed) { ex =>
        ex mustBe an[IllegalArgumentException]
        ex.getMessage must include("Cannot set status back to PENDING")
        verify(mockRepository, never()).reviewTreeChange(anyInt(), anyString(), any[Option[String]], any[ChangeStatus])
      }
    }
  }

  "TreeVersioningService.approveAllPending" should {

    "bulk approve all pending changes" in {
      when(mockRepository.applyAllPendingChanges(1))
        .thenReturn(Future.successful(15))

      whenReady(service.approveAllPending(1, "curator123")) { count =>
        count mustBe 15
        verify(mockRepository).applyAllPendingChanges(1)
      }
    }
  }

  // ============================================================================
  // Comment Tests
  // ============================================================================

  "TreeVersioningService.addComment" should {

    "add a comment to a change set" in {
      when(mockRepository.addComment(any[ChangeSetComment]))
        .thenReturn(Future.successful(1))

      whenReady(service.addComment(1, "curator123", "This looks good", None)) { commentId =>
        commentId mustBe 1
        verify(mockRepository).addComment(org.mockito.ArgumentMatchers.argThat { (c: ChangeSetComment) =>
          c.changeSetId == 1 &&
          c.author == "curator123" &&
          c.content == "This looks good" &&
          c.treeChangeId.isEmpty
        })
      }
    }

    "add a comment linked to a specific change" in {
      when(mockRepository.addComment(any[ChangeSetComment]))
        .thenReturn(Future.successful(2))

      whenReady(service.addComment(1, "curator123", "Check this change", Some(100))) { commentId =>
        commentId mustBe 2
        verify(mockRepository).addComment(org.mockito.ArgumentMatchers.argThat { (c: ChangeSetComment) =>
          c.treeChangeId.contains(100)
        })
      }
    }
  }

  "TreeVersioningService.listComments" should {

    "return all comments for a change set" in {
      val comments = Seq(
        ChangeSetComment(Some(1), 1, None, "curator1", "Comment 1", now),
        ChangeSetComment(Some(2), 1, Some(100), "curator2", "Comment 2", now)
      )
      when(mockRepository.listComments(1))
        .thenReturn(Future.successful(comments))

      whenReady(service.listComments(1)) { result =>
        result must have size 2
        result.head.content mustBe "Comment 1"
      }
    }
  }

  // ============================================================================
  // Tree Diff Tests
  // ============================================================================

  "TreeVersioningService.getTreeDiff" should {

    "compute diff from change set with various change types" in {
      val changeSet = createChangeSet(1)
      val changes = Seq(
        createTreeChange(1, 1, TreeChangeType.Create).copy(
          haplogroupData = Some("""{"name":"R1b-NEW"}"""),
          newParentId = Some(50),
          createdHaplogroupId = Some(100)
        ),
        createTreeChange(2, 1, TreeChangeType.Reparent).copy(
          haplogroupId = Some(101),
          oldParentId = Some(50),
          newParentId = Some(60)
        ),
        createTreeChange(3, 1, TreeChangeType.Update).copy(
          haplogroupId = Some(102)
        ),
        createTreeChange(4, 1, TreeChangeType.AddVariant).copy(
          haplogroupId = Some(102),
          variantId = Some(200)
        )
      )

      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
      when(mockRepository.getChangesForChangeSet(1))
        .thenReturn(Future.successful(changes))
      when(mockRepository.getHaplogroupNamesById(any[Set[Int]]))
        .thenReturn(Future.successful(Map(101 -> "R-M269", 102 -> "R-U106", 100 -> "R-M343")))

      whenReady(service.getTreeDiff(1)) { diff =>
        diff.changeSetId mustBe 1
        diff.summary.totalChanges mustBe 4
        diff.summary.nodesAdded mustBe 1
        diff.summary.nodesReparented mustBe 1
        diff.summary.nodesModified mustBe 1  // Update and AddVariant grouped by haplogroup
        diff.summary.variantsAdded mustBe 1

        // Check entries
        diff.entries.count(_.diffType == DiffType.Added) mustBe 1
        diff.entries.count(_.diffType == DiffType.Reparented) mustBe 1
        diff.entries.count(_.diffType == DiffType.Modified) mustBe 1
      }
    }

    "return empty diff for non-existent change set" in {
      when(mockRepository.getChangeSet(999))
        .thenReturn(Future.successful(None))
      when(mockRepository.getChangesForChangeSet(999))
        .thenReturn(Future.successful(Seq.empty))
      when(mockRepository.getHaplogroupNamesById(any[Set[Int]]))
        .thenReturn(Future.successful(Map.empty[Int, String]))

      whenReady(service.getTreeDiff(999)) { diff =>
        diff.changeSetId mustBe 999
        diff.summary.totalChanges mustBe 0
        diff.entries mustBe empty
      }
    }
  }

  "TreeVersioningService.getActiveTreeDiff" should {

    "return diff for active change set" in {
      val changeSet = createChangeSet(1, status = ChangeSetStatus.UnderReview)
      when(mockRepository.getActiveChangeSet(HaplogroupType.Y))
        .thenReturn(Future.successful(Some(changeSet)))
      when(mockRepository.getChangeSet(1))
        .thenReturn(Future.successful(Some(changeSet)))
      when(mockRepository.getChangesForChangeSet(1))
        .thenReturn(Future.successful(Seq.empty))
      when(mockRepository.getHaplogroupNamesById(any[Set[Int]]))
        .thenReturn(Future.successful(Map.empty[Int, String]))

      whenReady(service.getActiveTreeDiff(HaplogroupType.Y)) { result =>
        result mustBe defined
        result.get.changeSetId mustBe 1
      }
    }

    "return None when no active change set" in {
      when(mockRepository.getActiveChangeSet(HaplogroupType.Y))
        .thenReturn(Future.successful(None))

      whenReady(service.getActiveTreeDiff(HaplogroupType.Y)) { result =>
        result mustBe empty
      }
    }
  }

  "TreeVersioningService.getChangesForDiff" should {

    "return all changes for a change set" in {
      val changes = Seq(
        createTreeChange(1, 1, TreeChangeType.Create),
        createTreeChange(2, 1, TreeChangeType.Update)
      )
      when(mockRepository.getChangesForChangeSet(1))
        .thenReturn(Future.successful(changes))

      whenReady(service.getChangesForDiff(1)) { result =>
        result must have size 2
      }
    }
  }

  // ============================================================================
  // MT DNA Tests
  // ============================================================================

  "TreeVersioningService" should {

    "handle MT DNA haplogroup type correctly" in {
      when(mockRepository.getActiveChangeSet(HaplogroupType.MT))
        .thenReturn(Future.successful(None))
      when(mockRepository.createChangeSet(any[ChangeSet]))
        .thenReturn(Future.successful(2))
      when(mockRepository.getChangeSet(2))
        .thenReturn(Future.successful(Some(createChangeSet(2, haplogroupType = HaplogroupType.MT))))

      whenReady(service.createChangeSet(HaplogroupType.MT, "PhyloTree")) { result =>
        result.haplogroupType mustBe HaplogroupType.MT
      }
    }
  }
}
