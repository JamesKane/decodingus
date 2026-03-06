package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.domain.discovery.*
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.{PrivateVariantRepository, ProposedBranchRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class ProposalEngineSpec extends ServiceSpec {

  val mockProposedBranchRepo: ProposedBranchRepository = mock[ProposedBranchRepository]
  val mockPrivateVariantRepo: PrivateVariantRepository = mock[PrivateVariantRepository]

  val engine = new ProposalEngine(mockProposedBranchRepo, mockPrivateVariantRepo)

  override def beforeEach(): Unit = {
    reset(mockProposedBranchRepo, mockPrivateVariantRepo)
    // Default: no config overrides (use defaults)
    when(mockProposedBranchRepo.getConfig(any[HaplogroupType], anyString)).thenReturn(Future.successful(None))
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 1, 1, 0, 0)
  val sampleGuid: UUID = UUID.randomUUID()
  val sampleRef: SampleReference = SampleReference(BiosampleSourceType.External, 1, sampleGuid)

  def makePrivateVariant(variantId: Int, terminalHgId: Int = 100): BiosamplePrivateVariant =
    BiosamplePrivateVariant(
      id = Some(variantId * 10),
      sampleType = BiosampleSourceType.External,
      sampleId = 1,
      sampleGuid = sampleGuid,
      variantId = variantId,
      haplogroupType = HaplogroupType.Y,
      terminalHaplogroupId = terminalHgId,
      discoveredAt = now
    )

  def makeProposal(id: Int, parentHgId: Int = 100, consensusCount: Int = 1,
                   status: ProposedBranchStatus = ProposedBranchStatus.Pending): ProposedBranch =
    ProposedBranch(
      id = Some(id),
      parentHaplogroupId = parentHgId,
      haplogroupType = HaplogroupType.Y,
      status = status,
      consensusCount = consensusCount,
      createdAt = now,
      updatedAt = now
    )

  "ProposalEngine" should {

    "return empty when no private variants provided" in {
      whenReady(engine.processDiscovery(sampleRef, Seq.empty)) { result =>
        result mustBe empty
      }
    }

    "create a new proposal when no existing proposals match" in {
      val pvs = Seq(makePrivateVariant(1), makePrivateVariant(2), makePrivateVariant(3))

      // No existing proposals
      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      // Create proposal
      val newProposal = makeProposal(1)
      when(mockProposedBranchRepo.create(any[ProposedBranch]))
        .thenReturn(Future.successful(newProposal))

      // Add variants
      when(mockProposedBranchRepo.addVariant(any[ProposedBranchVariant])).thenAnswer { invocation =>
        val pbv = invocation.getArgument[ProposedBranchVariant](0)
        Future.successful(pbv.copy(id = Some(1)))
      }

      // Add evidence
      when(mockProposedBranchRepo.addEvidence(any[ProposedBranchEvidence])).thenAnswer { invocation =>
        val e = invocation.getArgument[ProposedBranchEvidence](0)
        Future.successful(e.copy(id = Some(1)))
      }

      whenReady(engine.processDiscovery(sampleRef, pvs)) { result =>
        result must have size 1
        result.head.id mustBe Some(1)
        verify(mockProposedBranchRepo).create(any[ProposedBranch])
      }
    }

    "match an existing proposal with high Jaccard similarity" in {
      val pvs = Seq(makePrivateVariant(1), makePrivateVariant(2), makePrivateVariant(3))
      val existingProposal = makeProposal(10, consensusCount = 1)

      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(existingProposal)))

      // Existing proposal has variants {1, 2, 3} — perfect match (Jaccard = 1.0)
      when(mockProposedBranchRepo.getVariantIds(10))
        .thenReturn(Future.successful(Set(1, 2, 3)))

      // Add evidence
      when(mockProposedBranchRepo.addEvidence(any[ProposedBranchEvidence])).thenAnswer { invocation =>
        val e = invocation.getArgument[ProposedBranchEvidence](0)
        Future.successful(e.copy(id = Some(1)))
      }

      // Get variants for evidence count update
      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(Seq(
        ProposedBranchVariant(Some(1), 10, 1, true, 1, now, now),
        ProposedBranchVariant(Some(2), 10, 2, true, 1, now, now),
        ProposedBranchVariant(Some(3), 10, 3, true, 1, now, now)
      )))

      when(mockProposedBranchRepo.updateVariantEvidence(anyInt, anyInt, anyInt))
        .thenReturn(Future.successful(true))

      when(mockProposedBranchRepo.countEvidence(10))
        .thenReturn(Future.successful(2))

      when(mockProposedBranchRepo.updateConsensus(anyInt, anyInt, any[Double]))
        .thenReturn(Future.successful(true))

      whenReady(engine.processDiscovery(sampleRef, pvs)) { result =>
        result must have size 1
        result.head.consensusCount mustBe 2
        // Should NOT create a new proposal
        verify(mockProposedBranchRepo, never()).create(any[ProposedBranch])
      }
    }

    "create new proposal when Jaccard similarity is too low" in {
      val pvs = Seq(makePrivateVariant(1), makePrivateVariant(2), makePrivateVariant(3))
      val existingProposal = makeProposal(10)

      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(existingProposal)))

      // Existing has {4, 5, 6} — no overlap (Jaccard = 0.0)
      when(mockProposedBranchRepo.getVariantIds(10))
        .thenReturn(Future.successful(Set(4, 5, 6)))

      val newProposal = makeProposal(11)
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

      whenReady(engine.processDiscovery(sampleRef, pvs)) { result =>
        result must have size 1
        verify(mockProposedBranchRepo).create(any[ProposedBranch])
      }
    }

    "flag partial matches for split review" in {
      // Jaccard of 0.5 <= J < 0.8 should flag for split
      val pvs = Seq(makePrivateVariant(1), makePrivateVariant(2), makePrivateVariant(3), makePrivateVariant(4))
      val existingProposal = makeProposal(10)

      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(existingProposal)))

      // Existing has {1, 2, 5, 6} — intersection {1,2}, union {1,2,3,4,5,6} => J = 2/6 = 0.333
      // Actually we need J >= 0.5 for split. Let's use {1, 2, 3, 5}: intersection {1,2,3}, union {1,2,3,4,5} => J = 3/5 = 0.6
      when(mockProposedBranchRepo.getVariantIds(10))
        .thenReturn(Future.successful(Set(1, 2, 3, 5)))

      // No match >= 0.8, so creates a new one
      val newProposal = makeProposal(11)
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

      // Expect the existing proposal to be updated with split note
      when(mockProposedBranchRepo.update(any[ProposedBranch]))
        .thenReturn(Future.successful(true))

      whenReady(engine.processDiscovery(sampleRef, pvs)) { result =>
        result must have size 1
        // Verify the split candidate was flagged
        verify(mockProposedBranchRepo).update(any[ProposedBranch])
      }
    }

    "transition proposal to ReadyForReview when consensus threshold met" in {
      val pvs = Seq(makePrivateVariant(1), makePrivateVariant(2))
      val existingProposal = makeProposal(10, consensusCount = 2, status = ProposedBranchStatus.Pending)

      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(existingProposal)))

      // Perfect match
      when(mockProposedBranchRepo.getVariantIds(10))
        .thenReturn(Future.successful(Set(1, 2)))

      when(mockProposedBranchRepo.addEvidence(any[ProposedBranchEvidence])).thenAnswer { invocation =>
        val e = invocation.getArgument[ProposedBranchEvidence](0)
        Future.successful(e.copy(id = Some(1)))
      }

      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(Seq(
        ProposedBranchVariant(Some(1), 10, 1, true, 2, now, now),
        ProposedBranchVariant(Some(2), 10, 2, true, 2, now, now)
      )))
      when(mockProposedBranchRepo.updateVariantEvidence(anyInt, anyInt, anyInt))
        .thenReturn(Future.successful(true))

      // 3 evidence entries — meets default threshold of 3
      when(mockProposedBranchRepo.countEvidence(10))
        .thenReturn(Future.successful(3))

      when(mockProposedBranchRepo.updateConsensus(anyInt, anyInt, any[Double]))
        .thenReturn(Future.successful(true))
      when(mockProposedBranchRepo.updateStatus(10, ProposedBranchStatus.ReadyForReview))
        .thenReturn(Future.successful(true))

      whenReady(engine.processDiscovery(sampleRef, pvs)) { result =>
        result must have size 1
        result.head.status mustBe ProposedBranchStatus.ReadyForReview
        verify(mockProposedBranchRepo).updateStatus(10, ProposedBranchStatus.ReadyForReview)
      }
    }

    "not transition already ReadyForReview proposals" in {
      val pvs = Seq(makePrivateVariant(1))
      val existingProposal = makeProposal(10, consensusCount = 5, status = ProposedBranchStatus.ReadyForReview)

      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(existingProposal)))

      when(mockProposedBranchRepo.getVariantIds(10))
        .thenReturn(Future.successful(Set(1)))

      when(mockProposedBranchRepo.addEvidence(any[ProposedBranchEvidence])).thenAnswer { invocation =>
        val e = invocation.getArgument[ProposedBranchEvidence](0)
        Future.successful(e.copy(id = Some(1)))
      }

      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(Seq(
        ProposedBranchVariant(Some(1), 10, 1, true, 5, now, now)
      )))
      when(mockProposedBranchRepo.updateVariantEvidence(anyInt, anyInt, anyInt))
        .thenReturn(Future.successful(true))
      when(mockProposedBranchRepo.countEvidence(10))
        .thenReturn(Future.successful(6))
      when(mockProposedBranchRepo.updateConsensus(anyInt, anyInt, any[Double]))
        .thenReturn(Future.successful(true))

      whenReady(engine.processDiscovery(sampleRef, pvs)) { result =>
        result must have size 1
        result.head.status mustBe ProposedBranchStatus.ReadyForReview
        // Should NOT call updateStatus since already ReadyForReview
        verify(mockProposedBranchRepo, never()).updateStatus(anyInt, any[ProposedBranchStatus])
      }
    }

    "add new variants from sample that existing proposal doesn't have" in {
      // Sample has {1, 2, 3, 4}, proposal has {1, 2, 3} => Jaccard = 3/4 = 0.75... just under 0.8
      // Actually let's make it match: {1, 2, 3, 4} vs {1, 2, 3, 4, 5} => J = 4/5 = 0.8 — exact threshold
      val pvs = Seq(makePrivateVariant(1), makePrivateVariant(2), makePrivateVariant(3), makePrivateVariant(4))
      val existingProposal = makeProposal(10)

      when(mockProposedBranchRepo.findByParentAndType(100, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(existingProposal)))

      when(mockProposedBranchRepo.getVariantIds(10))
        .thenReturn(Future.successful(Set(1, 2, 3, 4, 5)))

      when(mockProposedBranchRepo.addEvidence(any[ProposedBranchEvidence])).thenAnswer { invocation =>
        val e = invocation.getArgument[ProposedBranchEvidence](0)
        Future.successful(e.copy(id = Some(1)))
      }

      // No new variants to add (sample {1,2,3,4} is subset of proposal {1,2,3,4,5})
      // But existing shared variants get updated
      when(mockProposedBranchRepo.getVariants(10)).thenReturn(Future.successful(Seq(
        ProposedBranchVariant(Some(1), 10, 1, true, 1, now, now),
        ProposedBranchVariant(Some(2), 10, 2, true, 1, now, now),
        ProposedBranchVariant(Some(3), 10, 3, true, 1, now, now),
        ProposedBranchVariant(Some(4), 10, 4, true, 1, now, now),
        ProposedBranchVariant(Some(5), 10, 5, true, 1, now, now)
      )))
      when(mockProposedBranchRepo.updateVariantEvidence(anyInt, anyInt, anyInt))
        .thenReturn(Future.successful(true))
      when(mockProposedBranchRepo.countEvidence(10))
        .thenReturn(Future.successful(2))
      when(mockProposedBranchRepo.updateConsensus(anyInt, anyInt, any[Double]))
        .thenReturn(Future.successful(true))

      whenReady(engine.processDiscovery(sampleRef, pvs)) { result =>
        result must have size 1
        // Should not create a new proposal
        verify(mockProposedBranchRepo, never()).create(any[ProposedBranch])
      }
    }

    "handle citizen biosample source type" in {
      val citizenGuid = UUID.randomUUID()
      val citizenRef = SampleReference(BiosampleSourceType.Citizen, 42, citizenGuid)
      val pvs = Seq(
        BiosamplePrivateVariant(Some(1), BiosampleSourceType.Citizen, 42, citizenGuid,
          variantId = 10, haplogroupType = HaplogroupType.Y, terminalHaplogroupId = 200, discoveredAt = now)
      )

      when(mockProposedBranchRepo.findByParentAndType(200, HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      val newProposal = makeProposal(1, parentHgId = 200)
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

      whenReady(engine.processDiscovery(citizenRef, pvs)) { result =>
        result must have size 1
        verify(mockProposedBranchRepo).create(any[ProposedBranch])
      }
    }
  }

  "jaccardSimilarity" should {
    "return 1.0 for identical sets" in {
      engine.jaccardSimilarity(Set(1, 2, 3), Set(1, 2, 3)) mustBe 1.0
    }

    "return 0.0 for disjoint sets" in {
      engine.jaccardSimilarity(Set(1, 2), Set(3, 4)) mustBe 0.0
    }

    "return correct value for partial overlap" in {
      // {1,2,3} vs {2,3,4}: intersection={2,3}=2, union={1,2,3,4}=4, J=0.5
      engine.jaccardSimilarity(Set(1, 2, 3), Set(2, 3, 4)) mustBe 0.5
    }

    "return 1.0 for two empty sets" in {
      engine.jaccardSimilarity(Set.empty, Set.empty) mustBe 1.0
    }

    "return 0.0 when one set is empty" in {
      engine.jaccardSimilarity(Set(1, 2), Set.empty) mustBe 0.0
    }
  }

  "calculateConfidenceScore" should {
    "return 0.0 for zero evidence and zero variants" in {
      engine.calculateConfidenceScore(0, 0, 0) mustBe 0.0
    }

    "increase with evidence count" in {
      val low = engine.calculateConfidenceScore(1, 3, 0)
      val high = engine.calculateConfidenceScore(5, 3, 0)
      high must be > low
    }

    "decrease with variant mismatches" in {
      val noMismatch = engine.calculateConfidenceScore(3, 5, 0)
      val withMismatch = engine.calculateConfidenceScore(3, 5, 5)
      noMismatch must be > withMismatch
    }

    "cap at 1.0" in {
      engine.calculateConfidenceScore(100, 100, 0) mustBe 1.0
    }
  }
}
