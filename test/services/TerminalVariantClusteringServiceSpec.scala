package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.domain.discovery.*
import models.domain.haplogroups.Haplogroup
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.{HaplogroupCoreRepository, PrivateVariantRepository, ProposedBranchRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class TerminalVariantClusteringServiceSpec extends ServiceSpec {

  val mockPrivateVariantRepo: PrivateVariantRepository = mock[PrivateVariantRepository]
  val mockProposedBranchRepo: ProposedBranchRepository = mock[ProposedBranchRepository]
  val mockCoreRepo: HaplogroupCoreRepository = mock[HaplogroupCoreRepository]

  // ProposalEngine needs its own repos
  val proposalEngine = new ProposalEngine(mockProposedBranchRepo, mockPrivateVariantRepo)

  val service = new TerminalVariantClusteringService(
    mockPrivateVariantRepo, proposalEngine, mockCoreRepo, mockProposedBranchRepo
  )

  override def beforeEach(): Unit = {
    reset(mockPrivateVariantRepo, mockProposedBranchRepo, mockCoreRepo)
    when(mockProposedBranchRepo.getConfig(any[HaplogroupType], anyString))
      .thenReturn(Future.successful(None))
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 6, 1, 12, 0)

  def makeGuid(): UUID = UUID.randomUUID()

  def makePV(sampleId: Int, sampleGuid: UUID, variantId: Int, terminalHgId: Int = 100): BiosamplePrivateVariant =
    BiosamplePrivateVariant(
      id = Some(sampleId * 100 + variantId),
      sampleType = BiosampleSourceType.External,
      sampleId = sampleId,
      sampleGuid = sampleGuid,
      variantId = variantId,
      haplogroupType = HaplogroupType.Y,
      terminalHaplogroupId = terminalHgId,
      discoveredAt = now,
      status = PrivateVariantStatus.Active
    )

  val parentHg: Haplogroup = Haplogroup(
    id = Some(100), name = "R-M269", lineage = Some("R>R-M269"),
    description = None, haplogroupType = HaplogroupType.Y,
    revisionId = 1, source = "backbone", confidenceLevel = "high",
    validFrom = now, validUntil = None
  )

  "groupBySample" should {
    "group variants by sample key" in {
      val guid1 = makeGuid()
      val guid2 = makeGuid()
      val pvs = Seq(
        makePV(1, guid1, 10), makePV(1, guid1, 20),
        makePV(2, guid2, 10), makePV(2, guid2, 30)
      )

      val result = service.groupBySample(pvs)
      result must have size 2
      result(SampleKey(BiosampleSourceType.External, 1, guid1)) mustBe Set(10, 20)
      result(SampleKey(BiosampleSourceType.External, 2, guid2)) mustBe Set(10, 30)
    }

    "handle empty input" in {
      service.groupBySample(Seq.empty) mustBe empty
    }
  }

  "findClusters" should {
    "find exact clusters when samples share identical variant sets" in {
      val guid1 = makeGuid()
      val guid2 = makeGuid()
      val guid3 = makeGuid()
      val sampleSets = Map(
        SampleKey(BiosampleSourceType.External, 1, guid1) -> Set(10, 20, 30),
        SampleKey(BiosampleSourceType.External, 2, guid2) -> Set(10, 20, 30),
        SampleKey(BiosampleSourceType.External, 3, guid3) -> Set(40, 50)
      )

      val clusters = service.findClusters(sampleSets, minClusterSize = 2, minVariantsPerCluster = 1)
      val exactClusters = clusters.filter(_.clusterType == ClusterType.Exact)
      exactClusters must have size 1
      exactClusters.head.variantIds mustBe Set(10, 20, 30)
      exactClusters.head.supportingSamples must have size 2
    }

    "return empty when fewer samples than minClusterSize" in {
      val guid1 = makeGuid()
      val sampleSets = Map(
        SampleKey(BiosampleSourceType.External, 1, guid1) -> Set(10, 20)
      )

      val clusters = service.findClusters(sampleSets, minClusterSize = 2, minVariantsPerCluster = 1)
      clusters mustBe empty
    }

    "respect minVariantsPerCluster threshold" in {
      val guid1 = makeGuid()
      val guid2 = makeGuid()
      val sampleSets = Map(
        SampleKey(BiosampleSourceType.External, 1, guid1) -> Set(10),
        SampleKey(BiosampleSourceType.External, 2, guid2) -> Set(10)
      )

      // Require at least 2 variants per cluster
      val clusters = service.findClusters(sampleSets, minClusterSize = 2, minVariantsPerCluster = 2)
      val exactClusters = clusters.filter(_.clusterType == ClusterType.Exact)
      exactClusters mustBe empty
    }

    "find core clusters from overlapping variant sets" in {
      val guid1 = makeGuid()
      val guid2 = makeGuid()
      val guid3 = makeGuid()
      // Samples share core {10, 20} but differ on additional variants
      val sampleSets = Map(
        SampleKey(BiosampleSourceType.External, 1, guid1) -> Set(10, 20, 30),
        SampleKey(BiosampleSourceType.External, 2, guid2) -> Set(10, 20, 40),
        SampleKey(BiosampleSourceType.External, 3, guid3) -> Set(10, 20, 50)
      )

      val clusters = service.findClusters(sampleSets, minClusterSize = 3, minVariantsPerCluster = 2)
      val coreClusters = clusters.filter(_.clusterType == ClusterType.Core)
      coreClusters.size must be >= 1
      // The core {10, 20} should appear as it's shared by all 3 samples
      coreClusters.exists(_.variantIds == Set(10, 20)) mustBe true
    }

    "not produce redundant subset clusters" in {
      val guid1 = makeGuid()
      val guid2 = makeGuid()
      val sampleSets = Map(
        SampleKey(BiosampleSourceType.External, 1, guid1) -> Set(10, 20, 30),
        SampleKey(BiosampleSourceType.External, 2, guid2) -> Set(10, 20, 30)
      )

      // Should produce one exact cluster, not additional core subsets
      val clusters = service.findClusters(sampleSets, minClusterSize = 2, minVariantsPerCluster = 1)
      val exactClusters = clusters.filter(_.clusterType == ClusterType.Exact)
      exactClusters must have size 1
      // Core clusters for subsets {10,20}, {10,30}, {20,30} should be filtered out
      // since {10,20,30} is a superset with same supporter count
      val coreClusters = clusters.filter(_.clusterType == ClusterType.Core)
      coreClusters mustBe empty
    }
  }

  "findCoreClusters" should {
    "find intersections across sample pairs" in {
      val guid1 = makeGuid()
      val guid2 = makeGuid()
      val sampleSets = Map(
        SampleKey(BiosampleSourceType.External, 1, guid1) -> Set(10, 20, 30),
        SampleKey(BiosampleSourceType.External, 2, guid2) -> Set(10, 20, 40)
      )

      val cores = service.findCoreClusters(sampleSets, minClusterSize = 2, minVariantsPerCluster = 2)
      cores must have size 1
      cores.head.variantIds mustBe Set(10, 20)
    }

    "return empty for insufficient samples" in {
      val guid1 = makeGuid()
      val sampleSets = Map(
        SampleKey(BiosampleSourceType.External, 1, guid1) -> Set(10, 20)
      )
      service.findCoreClusters(sampleSets, minClusterSize = 2, minVariantsPerCluster = 1) mustBe empty
    }
  }

  "clusterForTerminal" should {
    "cluster private variants and feed into proposal engine" in {
      val guid1 = makeGuid()
      val guid2 = makeGuid()
      val pvs = Seq(
        makePV(1, guid1, 10), makePV(1, guid1, 20),
        makePV(2, guid2, 10), makePV(2, guid2, 20)
      )

      when(mockPrivateVariantRepo.findByTerminalHaplogroup(100))
        .thenReturn(Future.successful(pvs))

      // No existing proposals
      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      // Create proposal
      val newProposal = ProposedBranch(
        id = Some(1), parentHaplogroupId = 100, haplogroupType = HaplogroupType.Y,
        consensusCount = 1, createdAt = now, updatedAt = now
      )
      when(mockProposedBranchRepo.create(any[ProposedBranch]))
        .thenReturn(Future.successful(newProposal))
      when(mockProposedBranchRepo.addVariant(any[ProposedBranchVariant])).thenAnswer { invocation =>
        val pbv = invocation.getArgument[ProposedBranchVariant](0)
        Future.successful(pbv.copy(id = Some(1)))
      }
      when(mockProposedBranchRepo.addEvidence(any[ProposedBranchEvidence])).thenAnswer { invocation =>
        val e = invocation.getArgument[ProposedBranchEvidence](0)
        Future.successful(e.copy(id = Some(1)))
      }
      when(mockProposedBranchRepo.getEvidence(1)).thenReturn(Future.successful(Seq.empty))

      // Name suggestion
      when(mockCoreRepo.findById(100)).thenReturn(Future.successful(Some(parentHg)))
      when(mockProposedBranchRepo.findById(1)).thenReturn(Future.successful(Some(newProposal)))
      when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))

      whenReady(service.clusterForTerminal(100, HaplogroupType.Y)) { result =>
        result.samplesAnalyzed mustBe 2
        result.clusters must not be empty
        result.proposalsCreated must be >= 1
      }
    }

    "return empty result when no private variants exist" in {
      when(mockPrivateVariantRepo.findByTerminalHaplogroup(100))
        .thenReturn(Future.successful(Seq.empty))

      whenReady(service.clusterForTerminal(100, HaplogroupType.Y)) { result =>
        result.samplesAnalyzed mustBe 0
        result.clusters mustBe empty
        result.proposalsCreated mustBe 0
      }
    }

    "skip invalidated private variants" in {
      val guid1 = makeGuid()
      val pvs = Seq(
        makePV(1, guid1, 10).copy(status = PrivateVariantStatus.Invalidated),
        makePV(1, guid1, 20).copy(status = PrivateVariantStatus.Invalidated)
      )

      when(mockPrivateVariantRepo.findByTerminalHaplogroup(100))
        .thenReturn(Future.successful(pvs))

      whenReady(service.clusterForTerminal(100, HaplogroupType.Y)) { result =>
        result.samplesAnalyzed mustBe 0
        result.clusters mustBe empty
      }
    }
  }

  "suggestBranchName" should {
    "generate name from parent haplogroup" in {
      when(mockCoreRepo.findById(100)).thenReturn(Future.successful(Some(parentHg)))

      whenReady(service.suggestBranchName(100, 0)) { result =>
        result mustBe Some("R-M269-proposed-1")
      }
    }

    "return None when parent not found" in {
      when(mockCoreRepo.findById(999)).thenReturn(Future.successful(None))

      whenReady(service.suggestBranchName(999, 0)) { result =>
        result mustBe None
      }
    }
  }
}
