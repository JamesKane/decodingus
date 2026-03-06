package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.discovery.*
import play.api.Logging
import repositories.{PrivateVariantRepository, ProposedBranchRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Creates and updates proposed branches based on shared private variants
 * across biosamples. Uses Jaccard similarity to match incoming variant sets
 * against existing proposals under the same parent haplogroup.
 */
class ProposalEngine @Inject()(
  proposedBranchRepo: ProposedBranchRepository,
  privateVariantRepo: PrivateVariantRepository
)(implicit ec: ExecutionContext) extends Logging {

  private val DefaultJaccardMatchThreshold = 0.8
  private val DefaultJaccardSplitLower = 0.5

  /**
   * Process a biosample's private variants and create/update proposals.
   * Groups variants by parent haplogroup, then matches against existing proposals.
   */
  def processDiscovery(
    sampleRef: SampleReference,
    privateVariants: Seq[BiosamplePrivateVariant]
  ): Future[Seq[ProposedBranch]] = {
    if (privateVariants.isEmpty) return Future.successful(Seq.empty)

    // Group by (terminalHaplogroupId, haplogroupType) since a sample could have
    // private variants under different terminals (unlikely but possible)
    val grouped = privateVariants.groupBy(pv => (pv.terminalHaplogroupId, pv.haplogroupType))

    Future.sequence(grouped.toSeq.map { case ((parentHgId, hgType), pvs) =>
      val variantIds = pvs.map(_.variantId).toSet
      findOrCreateProposal(parentHgId, hgType, variantIds, sampleRef)
    })
  }

  /**
   * Find an existing proposal matching the variant set (Jaccard >= 0.8),
   * or create a new one. Adds evidence for the biosample either way.
   */
  def findOrCreateProposal(
    parentHaplogroupId: Int,
    haplogroupType: HaplogroupType,
    variantIds: Set[Int],
    sampleRef: SampleReference
  ): Future[ProposedBranch] = {
    for {
      // Get configurable thresholds
      matchThreshold <- getConfigDouble(haplogroupType, "jaccard_match_threshold", DefaultJaccardMatchThreshold)
      splitLower <- getConfigDouble(haplogroupType, "jaccard_split_threshold", DefaultJaccardSplitLower)

      // Find all active proposals under this parent
      existingProposals <- proposedBranchRepo.findByParentAndType(parentHaplogroupId, haplogroupType)

      // Calculate Jaccard similarity for each
      scored <- Future.sequence(existingProposals.map { proposal =>
        proposedBranchRepo.getVariantIds(proposal.id.get).map { proposalVariantIds =>
          val jaccard = jaccardSimilarity(variantIds, proposalVariantIds)
          (proposal, proposalVariantIds, jaccard)
        }
      })

      // Find best match
      bestMatch = scored.filter(_._3 >= matchThreshold).sortBy(-_._3).headOption
      splitCandidates = scored.filter(s => s._3 >= splitLower && s._3 < matchThreshold)

      result <- bestMatch match {
        case Some((proposal, proposalVariantIds, jaccard)) =>
          // Match found — add evidence and update
          addEvidenceToProposal(proposal, variantIds, proposalVariantIds, sampleRef)

        case None =>
          // No match — create new proposal
          createNewProposal(parentHaplogroupId, haplogroupType, variantIds, sampleRef)
      }

      // Flag split candidates (update notes but don't change status)
      _ <- Future.sequence(splitCandidates.map { case (proposal, _, jaccard) =>
        val splitNote = s"Partial match (Jaccard=${"%.2f".format(jaccard)}) with sample ${sampleRef.sampleGuid} — may need split review"
        val existingNotes = proposal.notes.map(_ + "; ").getOrElse("")
        proposedBranchRepo.update(proposal.copy(notes = Some(existingNotes + splitNote)))
      })
    } yield result
  }

  /**
   * Add a biosample as evidence to an existing proposal.
   * Updates variant evidence counts and recalculates consensus.
   */
  private def addEvidenceToProposal(
    proposal: ProposedBranch,
    sampleVariantIds: Set[Int],
    proposalVariantIds: Set[Int],
    sampleRef: SampleReference
  ): Future[ProposedBranch] = {
    val matchCount = (sampleVariantIds intersect proposalVariantIds).size
    val mismatchCount = (sampleVariantIds diff proposalVariantIds).size

    for {
      // Add evidence record
      _ <- proposedBranchRepo.addEvidence(ProposedBranchEvidence(
        proposedBranchId = proposal.id.get,
        sampleType = sampleRef.sampleType,
        sampleId = sampleRef.sampleId,
        sampleGuid = sampleRef.sampleGuid,
        variantMatchCount = matchCount,
        variantMismatchCount = mismatchCount
      ))

      // Add any new variants from this sample to the proposal
      newVariants = sampleVariantIds diff proposalVariantIds
      _ <- Future.sequence(newVariants.toSeq.map { vid =>
        proposedBranchRepo.addVariant(ProposedBranchVariant(
          proposedBranchId = proposal.id.get,
          variantId = vid,
          isDefining = false,
          evidenceCount = 1
        ))
      })

      // Update evidence counts for existing variants
      sharedVariants = sampleVariantIds intersect proposalVariantIds
      _ <- Future.sequence(sharedVariants.toSeq.map { vid =>
        proposedBranchRepo.getVariants(proposal.id.get).flatMap { variants =>
          variants.find(_.variantId == vid) match {
            case Some(pbv) =>
              proposedBranchRepo.updateVariantEvidence(proposal.id.get, vid, pbv.evidenceCount + 1)
            case None =>
              Future.successful(false)
          }
        }
      })

      // Recalculate consensus
      evidenceCount <- proposedBranchRepo.countEvidence(proposal.id.get)
      confidence = calculateConfidenceScore(evidenceCount, matchCount, mismatchCount)

      // Check thresholds
      updated <- updateConsensusAndThresholds(proposal, evidenceCount, confidence)
    } yield updated
  }

  /**
   * Create a new proposal from a set of private variants.
   */
  private def createNewProposal(
    parentHaplogroupId: Int,
    haplogroupType: HaplogroupType,
    variantIds: Set[Int],
    sampleRef: SampleReference
  ): Future[ProposedBranch] = {
    for {
      // Create proposal
      proposal <- proposedBranchRepo.create(ProposedBranch(
        parentHaplogroupId = parentHaplogroupId,
        haplogroupType = haplogroupType,
        consensusCount = 1,
        confidenceScore = 0.0
      ))

      // Add defining variants
      _ <- Future.sequence(variantIds.toSeq.map { vid =>
        proposedBranchRepo.addVariant(ProposedBranchVariant(
          proposedBranchId = proposal.id.get,
          variantId = vid,
          isDefining = true,
          evidenceCount = 1
        ))
      })

      // Add evidence
      _ <- proposedBranchRepo.addEvidence(ProposedBranchEvidence(
        proposedBranchId = proposal.id.get,
        sampleType = sampleRef.sampleType,
        sampleId = sampleRef.sampleId,
        sampleGuid = sampleRef.sampleGuid,
        variantMatchCount = variantIds.size,
        variantMismatchCount = 0
      ))

      _ = logger.info(s"Created new proposal ${proposal.id.get} under haplogroup $parentHaplogroupId " +
        s"with ${variantIds.size} defining variants from sample ${sampleRef.sampleGuid}")
    } yield proposal
  }

  /**
   * Update consensus count and check against thresholds.
   * Transitions: PENDING -> READY_FOR_REVIEW when consensus_threshold met.
   */
  private def updateConsensusAndThresholds(
    proposal: ProposedBranch,
    newConsensusCount: Int,
    newConfidence: Double
  ): Future[ProposedBranch] = {
    for {
      consensusThreshold <- getConfigInt(proposal.haplogroupType, "consensus_threshold", 3)
      autoPromoteThreshold <- getConfigInt(proposal.haplogroupType, "auto_promote_threshold", 10)
      confidenceThreshold <- getConfigDouble(proposal.haplogroupType, "confidence_threshold", 0.95)

      newStatus = proposal.status match {
        case ProposedBranchStatus.Pending if newConsensusCount >= consensusThreshold =>
          logger.info(s"Proposal ${proposal.id.get} reached consensus threshold ($newConsensusCount >= $consensusThreshold)")
          ProposedBranchStatus.ReadyForReview
        case other => other
      }

      _ <- proposedBranchRepo.updateConsensus(proposal.id.get, newConsensusCount, newConfidence)
      _ <- if (newStatus != proposal.status) {
        proposedBranchRepo.updateStatus(proposal.id.get, newStatus)
      } else {
        Future.successful(true)
      }
    } yield proposal.copy(
      consensusCount = newConsensusCount,
      confidenceScore = newConfidence,
      status = newStatus
    )
  }

  /**
   * Calculate Jaccard similarity: |A ∩ B| / |A ∪ B|
   */
  private[services] def jaccardSimilarity(a: Set[Int], b: Set[Int]): Double = {
    if (a.isEmpty && b.isEmpty) return 1.0
    val intersection = (a intersect b).size.toDouble
    val union = (a union b).size.toDouble
    if (union == 0) 0.0 else intersection / union
  }

  /**
   * Calculate a confidence score based on evidence strength.
   * Simplified version using sample count and variant consistency.
   */
  private[services] def calculateConfidenceScore(
    evidenceCount: Int,
    matchCount: Int,
    mismatchCount: Int
  ): Double = {
    val totalVariants = matchCount + mismatchCount
    val matchRatio = if (totalVariants > 0) matchCount.toDouble / totalVariants else 0.0

    // Weight: 60% sample count (capped at 10), 40% variant consistency
    val sampleScore = Math.min(evidenceCount / 10.0, 1.0)
    val score = 0.6 * sampleScore + 0.4 * matchRatio
    Math.min(score, 1.0)
  }

  private def getConfigDouble(hgType: HaplogroupType, key: String, default: Double): Future[Double] =
    proposedBranchRepo.getConfig(hgType, key).map(_.flatMap(_.toDoubleOption).getOrElse(default))

  private def getConfigInt(hgType: HaplogroupType, key: String, default: Int): Future[Int] =
    proposedBranchRepo.getConfig(hgType, key).map(_.flatMap(_.toIntOption).getOrElse(default))
}
