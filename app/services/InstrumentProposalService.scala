package services

import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.*
import play.api.Logging
import repositories.{InstrumentObservationRepository, InstrumentProposalRepository, SequencerInstrumentRepository, SequencingLabRepository}

import java.time.{LocalDateTime, Duration as JavaDuration}
import scala.concurrent.{ExecutionContext, Future}

case class InstrumentConflict(
                               instrumentId: String,
                               proposals: Seq[ConflictingLab],
                               dominantLabName: String,
                               dominantRatio: Double
                             )

case class ConflictingLab(
                           labName: String,
                           observationCount: Int,
                           ratio: Double
                         )

case class AggregationResult(
                              instrumentId: String,
                              dominantLabName: String,
                              observationCount: Int,
                              distinctCitizenCount: Int,
                              confidenceScore: Double,
                              conflict: Option[InstrumentConflict],
                              proposedManufacturer: Option[String],
                              proposedModel: Option[String],
                              earliestObservation: Option[LocalDateTime],
                              latestObservation: Option[LocalDateTime]
                            )

@Singleton
class InstrumentProposalService @Inject()(
                                           observationRepo: InstrumentObservationRepository,
                                           proposalRepo: InstrumentProposalRepository,
                                           instrumentRepo: SequencerInstrumentRepository,
                                           labRepo: SequencingLabRepository
                                         )(implicit ec: ExecutionContext) extends Logging {

  val MinObservationsForProposal: Int = 2
  val ReadyForReviewThreshold: Int = 5
  val AutoAcceptThreshold: Int = 10
  val MinDistinctCitizens: Int = 3
  val AgreementRatio: Double = 0.9

  private val ObservationWeight: Double = 0.4
  private val CitizenDiversityWeight: Double = 0.3
  private val RecencyWeight: Double = 0.2
  private val ConfidenceLevelWeight: Double = 0.1

  private val RecencyDays: Int = 30

  def aggregateObservations(instrumentId: String): Future[Option[AggregationResult]] = {
    observationRepo.findByInstrumentId(instrumentId).map { observations =>
      if (observations.size < MinObservationsForProposal) {
        None
      } else {
        Some(buildAggregation(instrumentId, observations))
      }
    }
  }

  private[services] def buildAggregation(instrumentId: String, observations: Seq[InstrumentObservation]): AggregationResult = {
    val labGroups = observations.groupBy(_.labName)
    val totalObs = observations.size

    val dominantLab = labGroups.maxBy(_._2.size)
    val dominantLabName = dominantLab._1
    val dominantCount = dominantLab._2.size
    val dominantRatio = dominantCount.toDouble / totalObs

    val distinctCitizens = observations.map(_.biosampleRef).distinct.size

    val conflict = if (labGroups.size > 1) {
      val conflictingLabs = labGroups.map { case (name, obs) =>
        ConflictingLab(name, obs.size, obs.size.toDouble / totalObs)
      }.toSeq.sortBy(-_.observationCount)

      Some(InstrumentConflict(instrumentId, conflictingLabs, dominantLabName, dominantRatio))
    } else None

    val confidenceScore = calculateConfidence(
      observations, totalObs, distinctCitizens
    )

    val platforms = dominantLab._2.flatMap(_.platform).groupBy(identity)
    val models = dominantLab._2.flatMap(_.instrumentModel).groupBy(identity)
    val proposedManufacturer = if (platforms.nonEmpty) Some(platforms.maxBy(_._2.size)._1) else None
    val proposedModel = if (models.nonEmpty) Some(models.maxBy(_._2.size)._1) else None

    val timestamps = observations.flatMap(o => Option(o.createdAt))
    val earliest = if (timestamps.nonEmpty) Some(timestamps.min) else None
    val latest = if (timestamps.nonEmpty) Some(timestamps.max) else None

    AggregationResult(
      instrumentId = instrumentId,
      dominantLabName = dominantLabName,
      observationCount = totalObs,
      distinctCitizenCount = distinctCitizens,
      confidenceScore = confidenceScore,
      conflict = conflict,
      proposedManufacturer = proposedManufacturer,
      proposedModel = proposedModel,
      earliestObservation = earliest,
      latestObservation = latest
    )
  }

  private[services] def calculateConfidence(
                                             observations: Seq[InstrumentObservation],
                                             observationCount: Int,
                                             distinctCitizens: Int
                                           ): Double = {
    val obsScore = math.min(observationCount.toDouble / AutoAcceptThreshold, 1.0)
    val citizenScore = math.min(distinctCitizens.toDouble / MinDistinctCitizens, 1.0)
    val recencyScore = calculateRecencyScore(observations)
    val confidenceLevelScore = calculateAvgConfidenceLevel(observations)

    val raw = ObservationWeight * obsScore +
      CitizenDiversityWeight * citizenScore +
      RecencyWeight * recencyScore +
      ConfidenceLevelWeight * confidenceLevelScore

    math.min(math.max(raw, 0.0), 1.0)
  }

  private[services] def calculateRecencyScore(observations: Seq[InstrumentObservation]): Double = {
    if (observations.isEmpty) return 0.0
    val now = LocalDateTime.now()
    val mostRecent = observations.map(_.createdAt).max
    val daysSince = JavaDuration.between(mostRecent, now).toDays
    if (daysSince <= RecencyDays) 1.0
    else math.max(0.0, 1.0 - (daysSince - RecencyDays).toDouble / (RecencyDays * 3))
  }

  private[services] def calculateAvgConfidenceLevel(observations: Seq[InstrumentObservation]): Double = {
    if (observations.isEmpty) return 0.0
    val weights = observations.map { obs =>
      obs.confidence match {
        case ObservationConfidence.Known => 1.0
        case ObservationConfidence.Inferred => 0.7
        case ObservationConfidence.Guessed => 0.3
      }
    }
    weights.sum / weights.size
  }

  def createOrUpdateProposal(instrumentId: String): Future[Option[InstrumentAssociationProposal]] = {
    for {
      aggregationOpt <- aggregateObservations(instrumentId)
      result <- aggregationOpt match {
        case None => Future.successful(None)
        case Some(agg) =>
          if (agg.conflict.exists(_.dominantRatio < 0.7)) {
            logger.warn(s"Instrument $instrumentId has conflicting lab associations " +
              s"(dominant ratio: ${agg.conflict.map(_.dominantRatio).getOrElse(0.0)})")
          }
          upsertProposal(agg)
      }
    } yield result
  }

  private def upsertProposal(agg: AggregationResult): Future[Option[InstrumentAssociationProposal]] = {
    proposalRepo.findActiveByInstrumentId(agg.instrumentId).flatMap {
      case Some(existing) =>
        val newStatus = evaluateThreshold(agg, existing.status)
        val updated = existing.copy(
          proposedLabName = agg.dominantLabName,
          proposedManufacturer = agg.proposedManufacturer,
          proposedModel = agg.proposedModel,
          observationCount = agg.observationCount,
          distinctCitizenCount = agg.distinctCitizenCount,
          confidenceScore = agg.confidenceScore,
          earliestObservation = agg.earliestObservation,
          latestObservation = agg.latestObservation,
          status = newStatus
        )
        proposalRepo.update(updated).map(_ => Some(updated))

      case None =>
        val status = if (agg.observationCount >= ReadyForReviewThreshold) ProposalStatus.ReadyForReview
                     else ProposalStatus.Pending
        val proposal = InstrumentAssociationProposal(
          instrumentId = agg.instrumentId,
          proposedLabName = agg.dominantLabName,
          proposedManufacturer = agg.proposedManufacturer,
          proposedModel = agg.proposedModel,
          observationCount = agg.observationCount,
          distinctCitizenCount = agg.distinctCitizenCount,
          confidenceScore = agg.confidenceScore,
          earliestObservation = agg.earliestObservation,
          latestObservation = agg.latestObservation,
          status = status
        )
        proposalRepo.create(proposal).map(Some(_))
    }
  }

  private[services] def evaluateThreshold(agg: AggregationResult, currentStatus: ProposalStatus): ProposalStatus = {
    currentStatus match {
      case ProposalStatus.UnderReview | ProposalStatus.Accepted | ProposalStatus.Rejected | ProposalStatus.Superseded =>
        currentStatus
      case _ =>
        if (agg.observationCount >= ReadyForReviewThreshold) ProposalStatus.ReadyForReview
        else ProposalStatus.Pending
    }
  }

  def evaluateAllPendingProposals(): Future[Seq[InstrumentAssociationProposal]] = {
    proposalRepo.findPending().flatMap { proposals =>
      Future.sequence(proposals.map { proposal =>
        aggregateObservations(proposal.instrumentId).flatMap {
          case Some(agg) =>
            val newStatus = evaluateThreshold(agg, proposal.status)
            if (newStatus != proposal.status || agg.observationCount != proposal.observationCount) {
              val updated = proposal.copy(
                observationCount = agg.observationCount,
                distinctCitizenCount = agg.distinctCitizenCount,
                confidenceScore = agg.confidenceScore,
                status = newStatus
              )
              proposalRepo.update(updated).map(_ => updated)
            } else {
              Future.successful(proposal)
            }
          case None => Future.successful(proposal)
        }
      })
    }
  }

  def acceptProposal(
                       proposalId: Int,
                       curatorId: String,
                       labName: String,
                       manufacturer: Option[String],
                       model: Option[String],
                       notes: Option[String]
                     ): Future[Either[String, InstrumentAssociationProposal]] = {
    proposalRepo.findById(proposalId).flatMap {
      case None =>
        Future.successful(Left(s"Proposal $proposalId not found"))
      case Some(proposal) if proposal.status == ProposalStatus.Accepted =>
        Future.successful(Left(s"Proposal $proposalId is already accepted"))
      case Some(proposal) if proposal.status == ProposalStatus.Rejected =>
        Future.successful(Left(s"Proposal $proposalId is already rejected"))
      case Some(proposal) =>
        for {
          assocResponse <- instrumentRepo.associateLabWithInstrument(
            proposal.instrumentId, labName, manufacturer, model
          )
          accepted = proposal.copy(
            status = ProposalStatus.Accepted,
            reviewedAt = Some(LocalDateTime.now()),
            reviewedBy = Some(curatorId),
            reviewNotes = notes,
            acceptedLabId = Some(assocResponse.labId)
          )
          _ <- proposalRepo.update(accepted)
        } yield Right(accepted)
    }
  }

  def rejectProposal(
                       proposalId: Int,
                       curatorId: String,
                       reason: String
                     ): Future[Either[String, InstrumentAssociationProposal]] = {
    proposalRepo.findById(proposalId).flatMap {
      case None =>
        Future.successful(Left(s"Proposal $proposalId not found"))
      case Some(proposal) if proposal.status == ProposalStatus.Accepted =>
        Future.successful(Left(s"Proposal $proposalId is already accepted"))
      case Some(proposal) if proposal.status == ProposalStatus.Rejected =>
        Future.successful(Left(s"Proposal $proposalId is already rejected"))
      case Some(proposal) =>
        val rejected = proposal.copy(
          status = ProposalStatus.Rejected,
          reviewedAt = Some(LocalDateTime.now()),
          reviewedBy = Some(curatorId),
          reviewNotes = Some(reason)
        )
        proposalRepo.update(rejected).map(_ => Right(rejected))
    }
  }

  def detectConflicts(): Future[Seq[InstrumentConflict]] = {
    proposalRepo.findPending().flatMap { proposals =>
      Future.sequence(proposals.map { proposal =>
        observationRepo.findByInstrumentId(proposal.instrumentId).map { observations =>
          val labGroups = observations.groupBy(_.labName)
          if (labGroups.size > 1) {
            val total = observations.size
            val conflictingLabs = labGroups.map { case (name, obs) =>
              ConflictingLab(name, obs.size, obs.size.toDouble / total)
            }.toSeq.sortBy(-_.observationCount)
            val dominant = conflictingLabs.head
            Some(InstrumentConflict(proposal.instrumentId, conflictingLabs, dominant.labName, dominant.ratio))
          } else None
        }
      }).map(_.flatten)
    }
  }
}
