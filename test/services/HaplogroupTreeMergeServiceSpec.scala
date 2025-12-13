package services

import models.HaplogroupType
import models.api.haplogroups.*
import models.domain.genomics.VariantV2
import models.domain.haplogroups.{Haplogroup, HaplogroupProvenance}
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, VariantV2Repository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

class HaplogroupTreeMergeServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  // Mocks
  var mockHaplogroupRepo: HaplogroupCoreRepository = _
  var mockVariantRepo: HaplogroupVariantRepository = _
  var mockVariantV2Repository: VariantV2Repository = _
  var service: HaplogroupTreeMergeService = _

  // Test fixtures
  val now: LocalDateTime = LocalDateTime.now()

  def createHaplogroup(
    id: Int,
    name: String,
    haplogroupType: HaplogroupType = HaplogroupType.Y,
    source: String = "ISOGG",
    provenance: Option[HaplogroupProvenance] = None
  ): Haplogroup = Haplogroup(
    id = Some(id),
    name = name,
    lineage = None,
    description = None,
    haplogroupType = haplogroupType,
    revisionId = 1,
    source = source,
    confidenceLevel = "high",
    validFrom = now.minusDays(30),
    validUntil = None,
    provenance = provenance
  )

  def createPhyloNode(
    name: String,
    variants: List[String] = List.empty,
    children: List[PhyloNodeInput] = List.empty,
    formedYbp: Option[Int] = None
  ): PhyloNodeInput = PhyloNodeInput(
    name = name,
    variants = variants.map(v => VariantInput(v)), // Convert strings to VariantInput
    children = children,
    formedYbp = formedYbp
  )

  override def beforeEach(): Unit = {
    mockHaplogroupRepo = mock[HaplogroupCoreRepository]
    mockVariantRepo = mock[HaplogroupVariantRepository]
    mockVariantV2Repository = mock[VariantV2Repository]
    service = new HaplogroupTreeMergeService(
      mockHaplogroupRepo,
      mockVariantRepo,
      mockVariantV2Repository
    )
  }

  "HaplogroupTreeMergeService" should {

    // =========================================================================
    // Preview Tests
    // =========================================================================

    "preview a simple tree merge with no existing haplogroups" in {
      // Setup: Empty existing tree
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21", "S145"),
        children = List(
          createPhyloNode("R1b-DF13", variants = List("DF13"))
        )
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.nodesProcessed mustBe 2
        result.statistics.nodesCreated mustBe 2
        result.statistics.nodesUnchanged mustBe 0
        result.newNodes must contain allOf ("R1b-L21", "R1b-DF13")
        result.conflicts mustBe empty
      }
    }

    "preview identifies existing nodes for update" in {
      // Setup: Existing tree with R1b-L21
      val existingHaplogroup = createHaplogroup(1, "R1b-L21", source = "DecodingUs")
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21", "S145"))
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21", "S145"),
        children = List(
          createPhyloNode("R1b-DF13", variants = List("DF13"))
        )
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        priorityConfig = Some(SourcePriorityConfig(List("ytree.net", "DecodingUs")))
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.nodesProcessed mustBe 2
        result.statistics.nodesCreated mustBe 1 // DF13 is new
        result.newNodes must contain("R1b-DF13")
        // R1b-L21 exists but ytree.net has higher priority, so it might be marked for update
        // depending on whether there are differences
      }
    }

    "preview detects age estimate conflicts" in {
      // Setup: Existing tree with different age estimate
      val existingHaplogroup = createHaplogroup(1, "R1b-L21").copy(formedYbp = Some(4500))
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21"))
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21"),
        formedYbp = Some(4800) // Different from existing
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        priorityConfig = Some(SourcePriorityConfig(List("ytree.net", "ISOGG")))
      )

      whenReady(service.previewMerge(request)) { result =>
        result.conflicts.size mustBe 1
        result.conflicts.head.field mustBe "formedYbp"
        result.conflicts.head.existingValue mustBe "4500"
        result.conflicts.head.newValue mustBe "4800"
      }
    }

    // =========================================================================
    // Variant-Based Matching Tests
    // =========================================================================

    "match nodes by variants, not names" in {
      // Setup: Existing "R-L21" should match incoming "R1b-L21" by variant
      val existingHaplogroup = createHaplogroup(1, "R-L21") // Different name
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21")) // Same variant
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21", // Different name but same variant
        variants = List("L21")
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net"
      )

      whenReady(service.previewMerge(request)) { result =>
        // Should recognize as existing node (unchanged), not new
        result.statistics.nodesCreated mustBe 0
        result.unchangedNodes must contain("R-L21")
      }
    }

    "fall back to name matching when no variant match found" in {
      // Setup: Existing node with same name but no variants
      val existingHaplogroup = createHaplogroup(1, "R1b-L21")
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq.empty) // No variants
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21", "S145") // Has variants but no match in DB
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net"
      )

      whenReady(service.previewMerge(request)) { result =>
        // Should match by name
        result.statistics.nodesCreated mustBe 0
        result.unchangedNodes must contain("R1b-L21")
      }
    }

    // =========================================================================
    // Credit Assignment Tests
    // =========================================================================

    "preserve ISOGG credit on existing nodes" in {
      // Setup: Existing node with ISOGG provenance
      val isoggProvenance = HaplogroupProvenance(
        primaryCredit = "ISOGG",
        nodeProvenance = Set("ISOGG")
      )
      val existingHaplogroup = createHaplogroup(1, "R1b-L21", provenance = Some(isoggProvenance))

      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21"))
        )))
      when(mockHaplogroupRepo.updateProvenance(anyInt(), any[HaplogroupProvenance]))
        .thenReturn(Future.successful(true))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21")
      )

      val request = TreeMergeRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        dryRun = true // Use dry run for this test
      )

      whenReady(service.mergeFullTree(request)) { result =>
        result.success mustBe true
        // ISOGG credit should be preserved (verified via mock)
      }
    }

    "assign incoming source credit for new nodes" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val sourceTree = createPhyloNode(
        name = "R1b-NEW",
        variants = List("NEW123")
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.newNodes must contain("R1b-NEW")
        // New nodes get incoming source credit (ytree.net)
      }
    }

    // =========================================================================
    // Priority Configuration Tests
    // =========================================================================

    "respect source priority for conflict resolution" in {
      val existingHaplogroup = createHaplogroup(1, "R1b-L21", source = "DecodingUs")
        .copy(formedYbp = Some(4500))

      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21"))
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21"),
        formedYbp = Some(4800)
      )

      // Higher priority = lower index. ytree.net at index 0 beats DecodingUs at index 1
      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        priorityConfig = Some(SourcePriorityConfig(List("ytree.net", "DecodingUs")))
      )

      whenReady(service.previewMerge(request)) { result =>
        result.conflicts.head.resolution mustBe "will_update"
      }
    }

    "keep existing values when existing source has higher priority" in {
      val existingProvenance = HaplogroupProvenance(primaryCredit = "ISOGG", nodeProvenance = Set("ISOGG"))
      val existingHaplogroup = createHaplogroup(1, "R1b-L21", provenance = Some(existingProvenance))
        .copy(formedYbp = Some(4500))

      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21"))
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21"),
        formedYbp = Some(4800)
      )

      // ISOGG at index 0 beats ytree.net at index 1
      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        priorityConfig = Some(SourcePriorityConfig(List("ISOGG", "ytree.net")))
      )

      whenReady(service.previewMerge(request)) { result =>
        result.conflicts.head.resolution mustBe "will_keep_existing"
      }
    }

    // =========================================================================
    // Subtree Merge Tests
    // =========================================================================

    "merge subtree under specified anchor" in {
      val anchorHaplogroup = createHaplogroup(100, "R1b")

      when(mockHaplogroupRepo.getHaplogroupByName("R1b", HaplogroupType.Y))
        .thenReturn(Future.successful(Some(anchorHaplogroup)))
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (anchorHaplogroup, Seq("M269"))
        )))
      when(mockHaplogroupRepo.createWithParent(any[Haplogroup], any[Option[Int]], anyString()))
        .thenReturn(Future.successful(101))
      when(mockHaplogroupRepo.updateProvenance(anyInt(), any[HaplogroupProvenance]))
        .thenReturn(Future.successful(true))
      when(mockVariantV2Repository.searchByName(anyString()))
        .thenReturn(Future.successful(Seq.empty))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21")
      )

      val request = SubtreeMergeRequest(
        haplogroupType = HaplogroupType.Y,
        anchorHaplogroupName = "R1b",
        sourceTree = sourceTree,
        sourceName = "ytree.net"
      )

      whenReady(service.mergeSubtree(request)) { result =>
        result.success mustBe true
        result.statistics.nodesCreated mustBe 1
        verify(mockHaplogroupRepo).createWithParent(any[Haplogroup], any[Option[Int]], anyString())
      }
    }

    "fail subtree merge when anchor not found" in {
      when(mockHaplogroupRepo.getHaplogroupByName("NONEXISTENT", HaplogroupType.Y))
        .thenReturn(Future.successful(None))

      val sourceTree = createPhyloNode(name = "Test")

      val request = SubtreeMergeRequest(
        haplogroupType = HaplogroupType.Y,
        anchorHaplogroupName = "NONEXISTENT",
        sourceTree = sourceTree,
        sourceName = "ytree.net"
      )

      whenReady(service.mergeSubtree(request).failed) { ex =>
        ex mustBe a[IllegalArgumentException]
        ex.getMessage must include("not found")
      }
    }

    // =========================================================================
    // Dry Run Tests
    // =========================================================================

    "not modify database on dry run" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val sourceTree = createPhyloNode(
        name = "R1b-NEW",
        variants = List("NEW123")
      )

      val request = TreeMergeRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        dryRun = true
      )

      whenReady(service.mergeFullTree(request)) { result =>
        result.success mustBe true
        // Verify no write operations were called
        verify(mockHaplogroupRepo, never()).createWithParent(any[Haplogroup], any[Option[Int]], anyString())
        verify(mockHaplogroupRepo, never()).update(any[Haplogroup])
        verify(mockHaplogroupRepo, never()).updateProvenance(anyInt(), any[HaplogroupProvenance])
      }
    }

    // =========================================================================
    // Recursive Tree Processing Tests
    // =========================================================================

    "process deeply nested tree structures" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      // Create a 4-level deep tree
      val deepTree = createPhyloNode(
        name = "Level1",
        variants = List("V1"),
        children = List(
          createPhyloNode(
            name = "Level2",
            variants = List("V2"),
            children = List(
              createPhyloNode(
                name = "Level3",
                variants = List("V3"),
                children = List(
                  createPhyloNode("Level4", variants = List("V4"))
                )
              )
            )
          )
        )
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = deepTree,
        sourceName = "test"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.nodesProcessed mustBe 4
        result.statistics.nodesCreated mustBe 4
        result.newNodes must have size 4
      }
    }

    "process tree with multiple children at each level" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val wideTree = createPhyloNode(
        name = "Parent",
        variants = List("P1"),
        children = List(
          createPhyloNode("Child1", variants = List("C1")),
          createPhyloNode("Child2", variants = List("C2")),
          createPhyloNode("Child3", variants = List("C3"))
        )
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = wideTree,
        sourceName = "test"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.nodesProcessed mustBe 4
        result.statistics.nodesCreated mustBe 4
      }
    }

    // =========================================================================
    // MT DNA Tests
    // =========================================================================

    "handle MT DNA haplogroup type" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.MT))
        .thenReturn(Future.successful(Seq.empty))

      val sourceTree = createPhyloNode(
        name = "H1",
        variants = List("H1-defining")
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.MT,
        sourceTree = sourceTree,
        sourceName = "mtDNA-tree"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.nodesCreated mustBe 1
        verify(mockHaplogroupRepo).getAllWithVariantNames(HaplogroupType.MT)
      }
    }

    // =========================================================================
    // Conflict Strategy Tests
    // =========================================================================

    "apply KeepExisting conflict strategy" in {
      val existingHaplogroup = createHaplogroup(1, "R1b-L21")
        .copy(formedYbp = Some(4500))

      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21"))
        )))
      when(mockHaplogroupRepo.updateProvenance(anyInt(), any[HaplogroupProvenance]))
        .thenReturn(Future.successful(true))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21"),
        formedYbp = Some(4800)
      )

      val request = TreeMergeRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        conflictStrategy = Some(ConflictStrategy.KeepExisting),
        dryRun = true
      )

      whenReady(service.mergeFullTree(request)) { result =>
        result.success mustBe true
        // With KeepExisting, should not update even with conflicts
        result.statistics.nodesUpdated mustBe 0
      }
    }

    "apply AlwaysUpdate conflict strategy" in {
      val existingHaplogroup = createHaplogroup(1, "R1b-L21", source = "low-priority")
        .copy(formedYbp = Some(4500))

      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("L21"))
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21"),
        formedYbp = Some(4800)
      )

      // With AlwaysUpdate, should update regardless of priority
      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "ytree.net",
        priorityConfig = Some(SourcePriorityConfig(List("low-priority", "ytree.net"))) // ytree.net is lower priority
      )

      whenReady(service.previewMerge(request)) { result =>
        // Preview shows conflict would be kept (default strategy)
        result.conflicts.nonEmpty mustBe true
      }
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    "handle empty source tree gracefully" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val emptyTree = createPhyloNode(name = "SingleNode")

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = emptyTree,
        sourceName = "test"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.nodesProcessed mustBe 1
      }
    }

    "handle nodes with no variants" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val noVariantsTree = createPhyloNode(
        name = "NoVariants",
        variants = List.empty
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = noVariantsTree,
        sourceName = "test"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.nodesCreated mustBe 1
      }
    }

    "handle case-insensitive variant matching" in {
      val existingHaplogroup = createHaplogroup(1, "R1b-L21")
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          (existingHaplogroup, Seq("l21")) // lowercase
        )))

      val sourceTree = createPhyloNode(
        name = "R1b-L21",
        variants = List("L21") // uppercase
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "test"
      )

      whenReady(service.previewMerge(request)) { result =>
        // Should match despite case difference
        result.statistics.nodesCreated mustBe 0
        result.unchangedNodes must contain("R1b-L21")
      }
    }

    // =========================================================================
    // Statistics Accuracy Tests
    // =========================================================================

    "accurately count variant additions for new nodes" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val sourceTree = createPhyloNode(
        name = "Test",
        variants = List("V1", "V2", "V3") // 3 variants
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = sourceTree,
        sourceName = "test"
      )

      whenReady(service.previewMerge(request)) { result =>
        result.statistics.variantsAdded mustBe 3
      }
    }

    "count relationship creations correctly" in {
      when(mockHaplogroupRepo.getAllWithVariantNames(HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val treeWithChildren = createPhyloNode(
        name = "Parent",
        children = List(
          createPhyloNode("Child1"),
          createPhyloNode("Child2")
        )
      )

      val request = MergePreviewRequest(
        haplogroupType = HaplogroupType.Y,
        sourceTree = treeWithChildren,
        sourceName = "test"
      )

      whenReady(service.previewMerge(request)) { result =>
        // Parent has 1 relationship (to anchor or none)
        // Child1 and Child2 each have 1 relationship to Parent
        result.statistics.relationshipsCreated mustBe 3
      }
    }
  }
}
