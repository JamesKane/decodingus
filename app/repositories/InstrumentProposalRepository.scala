package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.genomics.{InstrumentAssociationProposal, ProposalStatus}
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait InstrumentProposalRepository {
  def create(proposal: InstrumentAssociationProposal): Future[InstrumentAssociationProposal]
  def findById(id: Int): Future[Option[InstrumentAssociationProposal]]
  def findActiveByInstrumentId(instrumentId: String): Future[Option[InstrumentAssociationProposal]]
  def findByStatus(status: ProposalStatus): Future[Seq[InstrumentAssociationProposal]]
  def findPending(): Future[Seq[InstrumentAssociationProposal]]
  def update(proposal: InstrumentAssociationProposal): Future[Boolean]
  def updateStatus(id: Int, status: ProposalStatus): Future[Boolean]
}

@Singleton
class InstrumentProposalRepositoryImpl @Inject()(
                                                   override protected val dbConfigProvider: DatabaseConfigProvider
                                                 )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with InstrumentProposalRepository {

  import models.dal.MyPostgresProfile.api.*

  implicit private val statusMapper: BaseColumnType[ProposalStatus] =
    MappedColumnType.base[ProposalStatus, String](_.dbValue, ProposalStatus.fromString)

  private val proposals = DatabaseSchema.domain.genomics.instrumentAssociationProposals

  override def create(proposal: InstrumentAssociationProposal): Future[InstrumentAssociationProposal] = {
    db.run(
      (proposals returning proposals.map(_.id)
        into ((p, id) => p.copy(id = Some(id)))) += proposal
    )
  }

  override def findById(id: Int): Future[Option[InstrumentAssociationProposal]] = {
    db.run(proposals.filter(_.id === id).result.headOption)
  }

  override def findActiveByInstrumentId(instrumentId: String): Future[Option[InstrumentAssociationProposal]] = {
    val activeStatuses = Seq(ProposalStatus.Pending, ProposalStatus.ReadyForReview, ProposalStatus.UnderReview)
    db.run(
      proposals
        .filter(p => p.instrumentId === instrumentId && p.status.inSet(activeStatuses))
        .result.headOption
    )
  }

  override def findByStatus(status: ProposalStatus): Future[Seq[InstrumentAssociationProposal]] = {
    db.run(proposals.filter(_.status === status).sortBy(_.updatedAt.desc).result)
  }

  override def findPending(): Future[Seq[InstrumentAssociationProposal]] = {
    val activeStatuses = Seq(ProposalStatus.Pending, ProposalStatus.ReadyForReview)
    db.run(proposals.filter(_.status.inSet(activeStatuses)).sortBy(_.updatedAt.desc).result)
  }

  override def update(proposal: InstrumentAssociationProposal): Future[Boolean] = {
    db.run(
      proposals.filter(_.id === proposal.id)
        .map(p => (p.proposedLabName, p.proposedManufacturer, p.proposedModel,
          p.observationCount, p.distinctCitizenCount, p.confidenceScore,
          p.earliestObservation, p.latestObservation, p.status,
          p.reviewedAt, p.reviewedBy, p.reviewNotes,
          p.acceptedLabId, p.acceptedInstrumentId, p.updatedAt))
        .update((proposal.proposedLabName, proposal.proposedManufacturer, proposal.proposedModel,
          proposal.observationCount, proposal.distinctCitizenCount, proposal.confidenceScore,
          proposal.earliestObservation, proposal.latestObservation, proposal.status,
          proposal.reviewedAt, proposal.reviewedBy, proposal.reviewNotes,
          proposal.acceptedLabId, proposal.acceptedInstrumentId, LocalDateTime.now()))
    ).map(_ > 0)
  }

  override def updateStatus(id: Int, status: ProposalStatus): Future[Boolean] = {
    db.run(
      proposals.filter(_.id === id)
        .map(p => (p.status, p.updatedAt))
        .update((status, LocalDateTime.now()))
    ).map(_ > 0)
  }
}
