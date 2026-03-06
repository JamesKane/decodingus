package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.domain.discovery.*
import models.domain.genomics.BiosampleHaplogroup
import models.domain.haplogroups.Haplogroup
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.*

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class TreeEvolutionServiceSpec extends ServiceSpec {

  val mockCoreRepo: HaplogroupCoreRepository = mock[HaplogroupCoreRepository]
  val mockVariantRepo: HaplogroupVariantRepository = mock[HaplogroupVariantRepository]
  val mockProposedBranchRepo: ProposedBranchRepository = mock[ProposedBranchRepository]
  val mockPrivateVariantRepo: PrivateVariantRepository = mock[PrivateVariantRepository]
  val mockBhRepo: BiosampleHaplogroupRepository = mock[BiosampleHaplogroupRepository]
  val mockCuratorActionRepo: CuratorActionRepository = mock[CuratorActionRepository]

  val service = new TreeEvolutionService(
    mockCoreRepo, mockVariantRepo, mockProposedBranchRepo,
    mockPrivateVariantRepo, mockBhRepo, mockCuratorActionRepo
  )

  override def beforeEach(): Unit = {
    reset(mockCoreRepo, mockVariantRepo, mockProposedBranchRepo,
      mockPrivateVariantRepo, mockBhRepo, mockCuratorActionRepo)
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 6, 1, 12, 0)
  val curatorId = "curator@decodingus.org"

  val sampleGuid1: UUID = UUID.randomUUID()
  val sampleGuid2: UUID = UUID.randomUUID()

  def makeAcceptedProposal(id: Int, parentHgId: Int = 100): ProposedBranch =
    ProposedBranch(
      id = Some(id),
      parentHaplogroupId = parentHgId,
      proposedName = Some("R-Z9999"),
      haplogroupType = HaplogroupType.Y,
      status = ProposedBranchStatus.Accepted,
      consensusCount = 5,
      confidenceScore = 0.85,
      createdAt = now,
      updatedAt = now,
      reviewedAt = Some(now),
      reviewedBy = Some(curatorId)
    )

  "TreeEvolutionService" should {

    "promote an accepted proposal to the tree" in {
      val proposal = makeAcceptedProposal(10)

      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))

      // Create haplogroup
      when(mockCoreRepo.createWithParent(any[Haplogroup], any[Option[Int]], anyString))
        .thenReturn(Future.successful((500, Some(1))))

      // Defining variants
      val variants = Seq(
        ProposedBranchVariant(Some(1), 10, 42, isDefining = true, 5, now, now),
        ProposedBranchVariant(Some(2), 10, 43, isDefining = true, 5, now, now),
        ProposedBranchVariant(Some(3), 10, 44, isDefining = false, 2, now, now)
      )
      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(variants))
      when(mockVariantRepo.addVariantToHaplogroup(anyInt, anyInt)).thenReturn(Future.successful(1))

      // Evidence / biosample reassignment
      val evidence = Seq(
        ProposedBranchEvidence(Some(1), 10, BiosampleSourceType.External, 1, sampleGuid1, variantMatchCount = 2),
        ProposedBranchEvidence(Some(2), 10, BiosampleSourceType.Citizen, 42, sampleGuid2, variantMatchCount = 2)
      )
      when(mockProposedBranchRepo.getEvidence(10)).thenReturn(Future.successful(evidence))

      // Biosample haplogroup lookups
      when(mockBhRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(100), None))))
      when(mockBhRepo.findBySampleGuid(sampleGuid2))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid2, Some(100), None))))
      when(mockBhRepo.updateYHaplogroup(any[UUID], anyInt)).thenReturn(Future.successful(true))

      // Private variant promotion
      when(mockPrivateVariantRepo.findActiveByVariantIds(any[Set[Int]], any[HaplogroupType]))
        .thenReturn(Future.successful(Seq(
          BiosamplePrivateVariant(Some(101), BiosampleSourceType.External, 1, sampleGuid1, 42, HaplogroupType.Y, 100, now),
          BiosamplePrivateVariant(Some(102), BiosampleSourceType.Citizen, 42, sampleGuid2, 42, HaplogroupType.Y, 100, now)
        )))
      when(mockPrivateVariantRepo.updateStatus(anyInt, any[PrivateVariantStatus]))
        .thenReturn(Future.successful(true))

      // Update proposal
      when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))

      // Audit
      when(mockCuratorActionRepo.create(any[CuratorAction])).thenAnswer { invocation =>
        val a = invocation.getArgument[CuratorAction](0)
        Future.successful(a.copy(id = Some(1)))
      }

      whenReady(service.promoteProposal(10, curatorId)) { result =>
        result.proposalId mustBe 10
        result.newHaplogroupId mustBe 500
        result.haplogroupName mustBe "R-Z9999"
        result.definingVariantCount mustBe 2 // Only isDefining=true
        result.reassignedBiosampleCount mustBe 2
        result.promotedVariantCount must be >= 1

        // Verify haplogroup created
        verify(mockCoreRepo).createWithParent(any[Haplogroup], any[Option[Int]], anyString)
        // Verify only defining variants linked (2 not 3)
        verify(mockVariantRepo).addVariantToHaplogroup(500, 42)
        verify(mockVariantRepo).addVariantToHaplogroup(500, 43)
        // Verify audit
        verify(mockCuratorActionRepo).create(any[CuratorAction])
      }
    }

    "reject promotion of non-Accepted proposal" in {
      val pending = makeAcceptedProposal(10).copy(status = ProposedBranchStatus.Pending)
      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(pending)))

      whenReady(service.promoteProposal(10, curatorId).failed) { ex =>
        ex mustBe a[IllegalStateException]
        ex.getMessage must include("must be Accepted")
      }
    }

    "reject promotion when proposal has no name" in {
      val noName = makeAcceptedProposal(10).copy(proposedName = None)
      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(noName)))

      whenReady(service.promoteProposal(10, curatorId).failed) { ex =>
        ex mustBe a[IllegalStateException]
        ex.getMessage must include("no proposed name")
      }
    }

    "fail when proposal not found" in {
      when(mockProposedBranchRepo.findById(999)).thenReturn(Future.successful(None))

      whenReady(service.promoteProposal(999, curatorId).failed) { ex =>
        ex mustBe a[NoSuchElementException]
      }
    }

    "not reassign biosample if haplogroup doesn't match old terminal" in {
      val proposal = makeAcceptedProposal(10)
      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))
      when(mockCoreRepo.createWithParent(any[Haplogroup], any[Option[Int]], anyString))
        .thenReturn(Future.successful((500, Some(1))))

      val variants = Seq(ProposedBranchVariant(Some(1), 10, 42, isDefining = true, 3, now, now))
      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(variants))
      when(mockVariantRepo.addVariantToHaplogroup(anyInt, anyInt)).thenReturn(Future.successful(1))

      // Sample is assigned to haplogroup 200, not 100 (the parent)
      val evidence = Seq(ProposedBranchEvidence(Some(1), 10, BiosampleSourceType.External, 1, sampleGuid1))
      when(mockProposedBranchRepo.getEvidence(10)).thenReturn(Future.successful(evidence))
      when(mockBhRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(200), None))))

      when(mockPrivateVariantRepo.findActiveByVariantIds(any[Set[Int]], any[HaplogroupType]))
        .thenReturn(Future.successful(Seq.empty))
      when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))
      when(mockCuratorActionRepo.create(any[CuratorAction])).thenAnswer { invocation =>
        Future.successful(invocation.getArgument[CuratorAction](0).copy(id = Some(1)))
      }

      whenReady(service.promoteProposal(10, curatorId)) { result =>
        result.reassignedBiosampleCount mustBe 0
        verify(mockBhRepo, never()).updateYHaplogroup(any[UUID], anyInt)
      }
    }

    "handle MT haplogroup reassignment" in {
      val proposal = makeAcceptedProposal(10).copy(haplogroupType = HaplogroupType.MT)
      when(mockProposedBranchRepo.findById(10)).thenReturn(Future.successful(Some(proposal)))
      when(mockCoreRepo.createWithParent(any[Haplogroup], any[Option[Int]], anyString))
        .thenReturn(Future.successful((500, Some(1))))

      val variants = Seq(ProposedBranchVariant(Some(1), 10, 42, isDefining = true, 3, now, now))
      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(variants))
      when(mockVariantRepo.addVariantToHaplogroup(anyInt, anyInt)).thenReturn(Future.successful(1))

      val evidence = Seq(ProposedBranchEvidence(Some(1), 10, BiosampleSourceType.External, 1, sampleGuid1))
      when(mockProposedBranchRepo.getEvidence(10)).thenReturn(Future.successful(evidence))
      when(mockBhRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, None, Some(100)))))
      when(mockBhRepo.updateMtHaplogroup(any[UUID], anyInt)).thenReturn(Future.successful(true))

      when(mockPrivateVariantRepo.findActiveByVariantIds(any[Set[Int]], any[HaplogroupType]))
        .thenReturn(Future.successful(Seq.empty))
      when(mockProposedBranchRepo.update(any[ProposedBranch])).thenReturn(Future.successful(true))
      when(mockCuratorActionRepo.create(any[CuratorAction])).thenAnswer { invocation =>
        Future.successful(invocation.getArgument[CuratorAction](0).copy(id = Some(1)))
      }

      whenReady(service.promoteProposal(10, curatorId)) { result =>
        result.reassignedBiosampleCount mustBe 1
        verify(mockBhRepo).updateMtHaplogroup(sampleGuid1, 500)
      }
    }
  }

  "reassignBiosamplesToNewTerminal" should {
    "bulk reassign Y haplogroups" in {
      val guid1 = UUID.randomUUID()
      val guid2 = UUID.randomUUID()
      when(mockBhRepo.findByHaplogroupId(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(
          BiosampleHaplogroup(guid1, Some(100), None),
          BiosampleHaplogroup(guid2, Some(100), None)
        )))
      when(mockBhRepo.updateYHaplogroup(any[UUID], anyInt)).thenReturn(Future.successful(true))

      whenReady(service.reassignBiosamplesToNewTerminal(100, 500, HaplogroupType.Y)) { count =>
        count mustBe 2
        verify(mockBhRepo).updateYHaplogroup(guid1, 500)
        verify(mockBhRepo).updateYHaplogroup(guid2, 500)
      }
    }

    "bulk reassign MT haplogroups" in {
      val guid1 = UUID.randomUUID()
      when(mockBhRepo.findByHaplogroupId(50, HaplogroupType.MT))
        .thenReturn(Future.successful(Seq(
          BiosampleHaplogroup(guid1, None, Some(50))
        )))
      when(mockBhRepo.updateMtHaplogroup(any[UUID], anyInt)).thenReturn(Future.successful(true))

      whenReady(service.reassignBiosamplesToNewTerminal(50, 500, HaplogroupType.MT)) { count =>
        count mustBe 1
        verify(mockBhRepo).updateMtHaplogroup(guid1, 500)
      }
    }

    "return 0 when no biosamples match" in {
      when(mockBhRepo.findByHaplogroupId(999, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      whenReady(service.reassignBiosamplesToNewTerminal(999, 500, HaplogroupType.Y)) { count =>
        count mustBe 0
      }
    }
  }
}
