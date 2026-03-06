package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.discovery.*
import models.domain.haplogroups.Haplogroup
import play.api.Logging
import play.api.libs.json.Json
import repositories.*

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Promotes accepted proposals to the canonical haplogroup tree and
 * reassigns biosamples to the newly created haplogroup branches.
 */
class TreeEvolutionService @Inject()(
  haplogroupCoreRepo: HaplogroupCoreRepository,
  haplogroupVariantRepo: HaplogroupVariantRepository,
  proposedBranchRepo: ProposedBranchRepository,
  privateVariantRepo: PrivateVariantRepository,
  biosampleHaplogroupRepo: BiosampleHaplogroupRepository,
  curatorActionRepo: CuratorActionRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Promote an accepted proposal to the canonical haplogroup tree.
   * Creates a new haplogroup, links its variants, reassigns biosamples,
   * and updates private variant statuses.
   */
  def promoteProposal(
    proposalId: Int,
    curatorId: String
  ): Future[PromotionResult] = {
    for {
      // Validate proposal
      proposalOpt <- proposedBranchRepo.findById(proposalId)
      proposal = proposalOpt.getOrElse(
        throw new NoSuchElementException(s"Proposal $proposalId not found")
      )
      _ = if (proposal.status != ProposedBranchStatus.Accepted) {
        throw new IllegalStateException(
          s"Proposal $proposalId is ${proposal.status}, must be Accepted to promote"
        )
      }
      branchName = proposal.proposedName.getOrElse(
        throw new IllegalStateException(s"Proposal $proposalId has no proposed name — curator must assign a name before promotion")
      )

      // Create the new haplogroup in the tree
      newHaplogroup = Haplogroup(
        name = branchName,
        lineage = None, // Will be computed by tree service
        description = Some(s"Promoted from discovery proposal $proposalId"),
        haplogroupType = proposal.haplogroupType,
        revisionId = 1,
        source = s"discovery:$proposalId",
        confidenceLevel = f"${proposal.confidenceScore}%.2f",
        validFrom = LocalDateTime.now(),
        validUntil = None
      )
      (newHaplogroupId, _) <- haplogroupCoreRepo.createWithParent(
        newHaplogroup, Some(proposal.parentHaplogroupId), s"discovery:$proposalId"
      )

      // Link defining variants to the new haplogroup
      proposalVariants <- proposedBranchRepo.getVariants(proposalId)
      definingVariants = proposalVariants.filter(_.isDefining)
      _ <- Future.sequence(definingVariants.map { pv =>
        haplogroupVariantRepo.addVariantToHaplogroup(newHaplogroupId, pv.variantId)
      })

      // Reassign biosamples
      evidence <- proposedBranchRepo.getEvidence(proposalId)
      reassignCount <- reassignBiosamples(
        evidence, proposal.parentHaplogroupId, newHaplogroupId, proposal.haplogroupType
      )

      // Update private variant statuses to PROMOTED
      variantIds = definingVariants.map(_.variantId)
      promotedCount <- promotePrivateVariants(variantIds, proposal.haplogroupType)

      // Update proposal status to PROMOTED
      updatedProposal = proposal.copy(
        status = ProposedBranchStatus.Promoted,
        promotedHaplogroupId = Some(newHaplogroupId),
        updatedAt = LocalDateTime.now()
      )
      _ <- proposedBranchRepo.update(updatedProposal)

      // Record audit trail
      _ <- curatorActionRepo.create(CuratorAction(
        curatorId = curatorId,
        actionType = CuratorActionType.Create,
        targetType = CuratorTargetType.Haplogroup,
        targetId = newHaplogroupId,
        previousState = Some(Json.toJson(proposal)),
        newState = Some(Json.obj(
          "haplogroupId" -> newHaplogroupId,
          "name" -> branchName,
          "parentId" -> proposal.parentHaplogroupId,
          "definingVariants" -> definingVariants.map(_.variantId),
          "reassignedBiosamples" -> reassignCount
        )),
        reason = Some(s"Promoted proposal $proposalId to haplogroup $branchName")
      ))

      _ = logger.info(s"Promoted proposal $proposalId as haplogroup $branchName (id=$newHaplogroupId): " +
        s"${definingVariants.size} variants, $reassignCount biosamples reassigned, $promotedCount variants promoted")
    } yield PromotionResult(
      proposalId = proposalId,
      newHaplogroupId = newHaplogroupId,
      haplogroupName = branchName,
      definingVariantCount = definingVariants.size,
      reassignedBiosampleCount = reassignCount,
      promotedVariantCount = promotedCount
    )
  }

  /**
   * Reassign biosamples from evidence to the new haplogroup.
   * Updates the biosample_haplogroup table for each supporting sample.
   */
  private def reassignBiosamples(
    evidence: Seq[ProposedBranchEvidence],
    oldHaplogroupId: Int,
    newHaplogroupId: Int,
    haplogroupType: HaplogroupType
  ): Future[Int] = {
    Future.sequence(evidence.map { e =>
      biosampleHaplogroupRepo.findBySampleGuid(e.sampleGuid).flatMap {
        case Some(bh) =>
          haplogroupType match {
            case HaplogroupType.Y =>
              if (bh.yHaplogroupId.contains(oldHaplogroupId)) {
                biosampleHaplogroupRepo.updateYHaplogroup(e.sampleGuid, newHaplogroupId).map(if (_) 1 else 0)
              } else Future.successful(0)
            case HaplogroupType.MT =>
              if (bh.mtHaplogroupId.contains(oldHaplogroupId)) {
                biosampleHaplogroupRepo.updateMtHaplogroup(e.sampleGuid, newHaplogroupId).map(if (_) 1 else 0)
              } else Future.successful(0)
          }
        case None =>
          logger.warn(s"No biosample_haplogroup record for sample ${e.sampleGuid}")
          Future.successful(0)
      }
    }).map(_.sum)
  }

  /**
   * Update private variant statuses from ACTIVE to PROMOTED
   * for variants that are now part of the canonical tree.
   */
  private def promotePrivateVariants(
    variantIds: Seq[Int],
    haplogroupType: HaplogroupType
  ): Future[Int] = {
    Future.sequence(variantIds.map { vid =>
      privateVariantRepo.findActiveByVariantIds(Set(vid), haplogroupType).flatMap { pvs =>
        Future.sequence(pvs.map { pv =>
          privateVariantRepo.updateStatus(pv.id.get, PrivateVariantStatus.Promoted)
        })
      }.map(_.count(identity))
    }).map(_.sum)
  }

  /**
   * Bulk reassign biosamples from one haplogroup to another.
   * Used when a branch is refined and existing samples need to move.
   */
  def reassignBiosamplesToNewTerminal(
    oldTerminalId: Int,
    newTerminalId: Int,
    haplogroupType: HaplogroupType
  ): Future[Int] = {
    for {
      affected <- biosampleHaplogroupRepo.findByHaplogroupId(oldTerminalId, haplogroupType)
      count <- Future.sequence(affected.map { bh =>
        haplogroupType match {
          case HaplogroupType.Y =>
            biosampleHaplogroupRepo.updateYHaplogroup(bh.sampleGuid, newTerminalId).map(if (_) 1 else 0)
          case HaplogroupType.MT =>
            biosampleHaplogroupRepo.updateMtHaplogroup(bh.sampleGuid, newTerminalId).map(if (_) 1 else 0)
        }
      }).map(_.sum)
    } yield count
  }
}

case class PromotionResult(
  proposalId: Int,
  newHaplogroupId: Int,
  haplogroupName: String,
  definingVariantCount: Int,
  reassignedBiosampleCount: Int,
  promotedVariantCount: Int
)

object PromotionResult {
  implicit val format: play.api.libs.json.OFormat[PromotionResult] = play.api.libs.json.Json.format
}
