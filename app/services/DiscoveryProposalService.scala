package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.discovery.*
import play.api.Logging
import play.api.libs.json.Json
import repositories.{CuratorActionRepository, ProposedBranchRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for curator operations on discovery proposals.
 * Handles accept/reject workflows with full audit trail.
 */
class DiscoveryProposalService @Inject()(
  proposedBranchRepo: ProposedBranchRepository,
  curatorActionRepo: CuratorActionRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * List proposals with optional filters.
   */
  def listProposals(
    haplogroupType: Option[HaplogroupType],
    status: Option[ProposedBranchStatus]
  ): Future[Seq[ProposedBranch]] = {
    status match {
      case Some(s) => proposedBranchRepo.findByStatus(s, haplogroupType)
      case None =>
        // Return all non-terminal statuses by default
        for {
          pending <- proposedBranchRepo.findByStatus(ProposedBranchStatus.Pending, haplogroupType)
          ready <- proposedBranchRepo.findByStatus(ProposedBranchStatus.ReadyForReview, haplogroupType)
          review <- proposedBranchRepo.findByStatus(ProposedBranchStatus.UnderReview, haplogroupType)
          accepted <- proposedBranchRepo.findByStatus(ProposedBranchStatus.Accepted, haplogroupType)
        } yield pending ++ ready ++ review ++ accepted
    }
  }

  /**
   * Get proposal details including variants and evidence.
   */
  def getProposalDetails(proposalId: Int): Future[Option[ProposalDetailsView]] = {
    proposedBranchRepo.findById(proposalId).flatMap {
      case None => Future.successful(None)
      case Some(proposal) =>
        for {
          variants <- proposedBranchRepo.getVariants(proposalId)
          evidence <- proposedBranchRepo.getEvidence(proposalId)
          actions <- curatorActionRepo.findByTarget(CuratorTargetType.ProposedBranch, proposalId)
        } yield Some(ProposalDetailsView(proposal, variants, evidence, actions))
    }
  }

  /**
   * Accept a proposal. Sets status to Accepted and records audit trail.
   */
  def acceptProposal(
    proposalId: Int,
    curatorId: String,
    proposedName: String,
    reason: Option[String]
  ): Future[ProposedBranch] = {
    for {
      proposalOpt <- proposedBranchRepo.findById(proposalId)
      proposal = proposalOpt.getOrElse(
        throw new NoSuchElementException(s"Proposal $proposalId not found")
      )
      _ = validateStatusTransition(proposal.status, ProposedBranchStatus.Accepted)

      previousState = Json.toJson(proposal)
      now = LocalDateTime.now()
      updated = proposal.copy(
        status = ProposedBranchStatus.Accepted,
        proposedName = Some(proposedName),
        reviewedAt = Some(now),
        reviewedBy = Some(curatorId),
        notes = reason.orElse(proposal.notes),
        updatedAt = now
      )
      _ <- proposedBranchRepo.update(updated)

      _ <- curatorActionRepo.create(CuratorAction(
        curatorId = curatorId,
        actionType = CuratorActionType.Accept,
        targetType = CuratorTargetType.ProposedBranch,
        targetId = proposalId,
        previousState = Some(previousState),
        newState = Some(Json.toJson(updated)),
        reason = reason
      ))

      _ = logger.info(s"Curator $curatorId accepted proposal $proposalId as '$proposedName'")
    } yield updated
  }

  /**
   * Reject a proposal with a reason. Records audit trail.
   */
  def rejectProposal(
    proposalId: Int,
    curatorId: String,
    reason: String
  ): Future[ProposedBranch] = {
    for {
      proposalOpt <- proposedBranchRepo.findById(proposalId)
      proposal = proposalOpt.getOrElse(
        throw new NoSuchElementException(s"Proposal $proposalId not found")
      )
      _ = validateStatusTransition(proposal.status, ProposedBranchStatus.Rejected)

      previousState = Json.toJson(proposal)
      now = LocalDateTime.now()
      updated = proposal.copy(
        status = ProposedBranchStatus.Rejected,
        reviewedAt = Some(now),
        reviewedBy = Some(curatorId),
        notes = Some(reason),
        updatedAt = now
      )
      _ <- proposedBranchRepo.update(updated)

      _ <- curatorActionRepo.create(CuratorAction(
        curatorId = curatorId,
        actionType = CuratorActionType.Reject,
        targetType = CuratorTargetType.ProposedBranch,
        targetId = proposalId,
        previousState = Some(previousState),
        newState = Some(Json.toJson(updated)),
        reason = Some(reason)
      ))

      _ = logger.info(s"Curator $curatorId rejected proposal $proposalId: $reason")
    } yield updated
  }

  /**
   * Start review of a proposal (transition to UnderReview).
   */
  def startReview(
    proposalId: Int,
    curatorId: String
  ): Future[ProposedBranch] = {
    for {
      proposalOpt <- proposedBranchRepo.findById(proposalId)
      proposal = proposalOpt.getOrElse(
        throw new NoSuchElementException(s"Proposal $proposalId not found")
      )
      _ = validateStatusTransition(proposal.status, ProposedBranchStatus.UnderReview)

      now = LocalDateTime.now()
      updated = proposal.copy(
        status = ProposedBranchStatus.UnderReview,
        reviewedBy = Some(curatorId),
        updatedAt = now
      )
      _ <- proposedBranchRepo.update(updated)

      _ <- curatorActionRepo.create(CuratorAction(
        curatorId = curatorId,
        actionType = CuratorActionType.Review,
        targetType = CuratorTargetType.ProposedBranch,
        targetId = proposalId,
        reason = Some("Started review")
      ))

      _ = logger.info(s"Curator $curatorId started review of proposal $proposalId")
    } yield updated
  }

  /**
   * Get audit trail for a proposal.
   */
  def getAuditTrail(proposalId: Int): Future[Seq[CuratorAction]] =
    curatorActionRepo.findByTarget(CuratorTargetType.ProposedBranch, proposalId)

  /**
   * Validate that a status transition is allowed.
   */
  private[services] def validateStatusTransition(
    current: ProposedBranchStatus,
    target: ProposedBranchStatus
  ): Unit = {
    val allowed = current match {
      case ProposedBranchStatus.Pending => Set(ProposedBranchStatus.ReadyForReview, ProposedBranchStatus.Rejected)
      case ProposedBranchStatus.ReadyForReview => Set(ProposedBranchStatus.UnderReview, ProposedBranchStatus.Rejected)
      case ProposedBranchStatus.UnderReview => Set(ProposedBranchStatus.Accepted, ProposedBranchStatus.Rejected, ProposedBranchStatus.Split)
      case ProposedBranchStatus.Accepted => Set(ProposedBranchStatus.Promoted, ProposedBranchStatus.Rejected)
      case _ => Set.empty[ProposedBranchStatus]
    }
    if (!allowed.contains(target)) {
      throw new IllegalStateException(
        s"Cannot transition proposal from $current to $target. Allowed: ${allowed.mkString(", ")}"
      )
    }
  }
}

/**
 * View model combining proposal with its variants, evidence, and audit trail.
 */
case class ProposalDetailsView(
  proposal: ProposedBranch,
  variants: Seq[ProposedBranchVariant],
  evidence: Seq[ProposedBranchEvidence],
  auditTrail: Seq[CuratorAction]
)

object ProposalDetailsView {
  implicit val format: play.api.libs.json.OFormat[ProposalDetailsView] = play.api.libs.json.Json.format
}
